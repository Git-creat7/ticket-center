$ErrorActionPreference = 'Continue'

# 中文输出需要 UTF-8：否则 Windows PowerShell 5.1 在重定向或管道时会按 ANSI 码页输出乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$benchmarkDir = $PSScriptRoot
# 路径统一用正斜杠：Windows 的 .NET 同样接受，而 Linux 上反斜杠只是普通字符，
# "$dir\results" 会拼成一个名字里带反斜杠的文件而不是子目录。
$resultsDir = "$benchmarkDir/results"
$reportsDir = "$benchmarkDir/reports"

# 从仓库根目录的 .env 读取凭据，避免把密码写进脚本
$envFile = Join-Path (Split-Path $benchmarkDir -Parent) '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            Set-Item -Path "env:$($matches[1])" -Value $matches[2].Trim()
        }
    }
}

Remove-Item env:JMETER_BIN -ErrorAction SilentlyContinue

# Linux/macOS 的启动器叫 jmeter（shell 脚本），没有 .bat。
# 判平台用 $env:OS 而不是 $IsWindows：后者在 Windows PowerShell 5.1 里是 $null。
$defaultJmeter = if ($env:OS -eq 'Windows_NT') { "jmeter.bat" } else { "jmeter" }
$jmeterExec = if ($env:JMETER_EXEC) { $env:JMETER_EXEC } else { $defaultJmeter }
if ($env:JMETER_EXEC) {
    $jmeterParent = Split-Path $env:JMETER_EXEC -Parent
    if ($jmeterParent -and (Test-Path $jmeterParent)) {
        $env:PATH = "$jmeterParent;$env:PATH"
    }
}
$dbUser = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { "root" }
$dbPassword = $env:DB_PASSWORD
$redisPassword = $env:TICKET_REDIS_PASSWORD

if (-not $dbPassword -or -not $redisPassword) {
    Write-Host ".env 中缺少 DB_PASSWORD 或 TICKET_REDIS_PASSWORD"
    exit 1
}

Write-Host "=========================================================="
Write-Host ">>> 开始执行 JMeter 全量压测套件"
Write-Host "=========================================================="

# 0. 重置秒杀库存 (ticket_id=3)
Write-Host '[步骤 0] 重置并预热秒杀库存 (ticket_id=3, 库存=50)...'
docker exec ticket-redis redis-cli -a $redisPassword set "tc:ticket:{3}:stock" 50 2>$null | Out-Null
docker exec ticket-redis redis-cli -a $redisPassword del "tc:ticket:{3}:order" 2>$null | Out-Null
docker exec ticket-mysql mysql "-u$dbUser" "-p$dbPassword" ticket_center -e "UPDATE tb_ticket_stock SET stock=50 WHERE ticket_id=3; DELETE FROM tb_ticket_order WHERE ticket_id=3;" 2>$null | Out-Null
Write-Host 'MySQL 与 Redis 库存已重置为 50'

# 清理上一轮的结果文件
Remove-Item -Path "$resultsDir/*" -Force -Recurse -ErrorAction SilentlyContinue
Remove-Item -Path "$reportsDir/*" -Force -Recurse -ErrorAction SilentlyContinue

# 1. 签到状态压测
Write-Host "`n[场景 1] 压测 GET /user/sign/status (200 线程 x 50 循环 = 10,000 样本)..."
& $jmeterExec -n -t "$benchmarkDir/1_sign_status_qps.jmx" -l "$resultsDir/sign_status.jtl" -e -o "$reportsDir/sign_status_report"

# 2. 演出详情压测
Write-Host "`n[场景 2] 压测 GET /event/1 (200 线程 x 20 循环 = 4,000 样本)..."
& $jmeterExec -n -t "$benchmarkDir/2_event_detail_qps.jmx" -l "$resultsDir/event_detail.jtl" -e -o "$reportsDir/event_detail_report"

# 3. 秒杀抢票压测
Write-Host "`n[场景 3] 压测 POST /ticket-orders/reserve/3 (100 线程 x 10 循环 = 1,000 样本)..."
& $jmeterExec -n -t "$benchmarkDir/3_seckill_reserve_qps.jmx" -l "$resultsDir/seckill_reserve.jtl" -e -o "$reportsDir/seckill_reserve_report"

# 等待 2 秒，让 RabbitMQ 异步消费者把订单落库
Start-Sleep -Seconds 2

Write-Host "`n=========================================================="
Write-Host ">>> 压测结束，开始解析 JTL 结果文件..."
Write-Host "=========================================================="

function Parse-Jtl($jtlFile, $label) {
    if (-not (Test-Path $jtlFile)) {
        Write-Host "结果文件不存在：$jtlFile"
        return
    }
    $lines = Get-Content $jtlFile
    if ($lines.Count -le 1) {
        Write-Host "结果文件为空：$jtlFile"
        return
    }
    $header = $lines[0].Split(',')
    $elapsedIdx = [array]::IndexOf($header, "elapsed")
    $successIdx = [array]::IndexOf($header, "success")
    $timeIdx = [array]::IndexOf($header, "timeStamp")

    $total = $lines.Count - 1
    $successCount = 0
    $totalElapsed = 0
    $minTime = [long]::MaxValue
    $maxTime = [long]::MinValue

    for ($i = 1; $i -lt $lines.Count; $i++) {
        $cols = $lines[$i].Split(',')
        if ($cols.Count -gt $elapsedIdx) {
            $elapsed = [long]$cols[$elapsedIdx]
            $totalElapsed += $elapsed
            if ($cols[$successIdx] -eq "true") {
                $successCount++
            }
            $ts = [long]$cols[$timeIdx]
            if ($ts -lt $minTime) { $minTime = $ts }
            if ($ts -gt $maxTime) { $maxTime = $ts }
        }
    }

    $avgElapsed = [math]::Round($totalElapsed / $total, 2)
    # 不要用 Max(1, ...) 兜底：样本跑完不足 1 秒时会把分母钉成 1 秒，
    # 导致 4000 样本被算成"4000 QPS"这类失真数字
    $durationSec = ($maxTime - $minTime) / 1000.0
    if ($durationSec -le 0) {
        Write-Host "  [警告] $label 首尾时间戳相同，无法计算 QPS"
        return
    }
    $qps = [math]::Round($total / $durationSec, 1)
    $errRate = [math]::Round((($total - $successCount) / $total) * 100, 2)

    Write-Host "[$label]"
    Write-Host "  样本总数:   $total"
    Write-Host "  吞吐量:     $qps req/s"
    Write-Host "  平均响应:   $avgElapsed ms"
    Write-Host "  错误率:     $errRate %"
}

Parse-Jtl "$resultsDir/sign_status.jtl" "场景 1：签到状态查询 GET /user/sign/status"
Write-Host ""
Parse-Jtl "$resultsDir/event_detail.jtl" "场景 2：演出详情聚合读 GET /event/1"
Write-Host ""
Parse-Jtl "$resultsDir/seckill_reserve.jtl" "场景 3：秒杀抢票并发写 POST /ticket-orders/reserve/3"

# 秒杀数据强一致性核对
$redisStock = (docker exec ticket-redis redis-cli -a $redisPassword get "tc:ticket:{3}:stock" 2>$null).Trim()
$redisOrders = (docker exec ticket-redis redis-cli -a $redisPassword scard "tc:ticket:{3}:order" 2>$null).Trim()
$dbOrders = (docker exec ticket-mysql mysql "-u$dbUser" "-p$dbPassword" ticket_center -se "SELECT COUNT(*) FROM tb_ticket_order WHERE ticket_id=3;" 2>$null).Trim()

Write-Host "`n----------------------------------------------------------"
Write-Host ">>> 秒杀强一致性核对（初始 50 张票，打入 1000 笔请求）："
Write-Host "  Redis 预扣库存结余: $redisStock (预期: 0)"
Write-Host "  Redis 抢票成功人数: $redisOrders (预期: 50)"
Write-Host "  MySQL 落库订单数:   $dbOrders (预期: 50)"
if ($redisStock -eq "0" -and $redisOrders -eq "50" -and $dbOrders -eq "50") {
    Write-Host "  核对结果: 通过 —— 零超卖、零少卖"
} else {
    Write-Host "  核对结果: 不一致，请检查"
}
Write-Host "----------------------------------------------------------"
Write-Host "HTML 报告已生成："
Write-Host "  1. $reportsDir/sign_status_report/index.html"
Write-Host "  2. $reportsDir/event_detail_report/index.html"
Write-Host "  3. $reportsDir/seckill_reserve_report/index.html"
Write-Host "=========================================================="
