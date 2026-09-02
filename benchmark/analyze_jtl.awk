#!/usr/bin/awk -f
# 从 JMeter .jtl 原始样本重算吞吐量与耗时分布。
# 用途：核对报告里的 QPS 是否与原始数据一致，并把 elapsed 拆成 Connect / Latency 两段，
# 判断耗时到底花在 TCP 建连还是服务端处理。
BEGIN { FS = ","; min_ts = 0; max_end = 0; n = 0 }
NR == 1 { next }
{
    ts = $1 + 0; elapsed = $2 + 0; code = $4; ok = $8;
    lat = $15 + 0; conn = $17 + 0;
    n++;
    if (min_ts == 0 || ts < min_ts) min_ts = ts;
    if (ts + elapsed > max_end) max_end = ts + elapsed;
    sum_e += elapsed; sum_l += lat; sum_c += conn;
    e[n] = elapsed; c[n] = conn;
    if (ok != "true") fail++;
    if (conn > 0) conn_nonzero++;
}
END {
    dur = (max_end - min_ts) / 1000.0;
    printf "样本数        : %d  (失败 %d)\n", n, fail;
    printf "实测时长      : %.2f s\n", dur;
    printf "吞吐量        : %.1f QPS\n", n / dur;
    printf "平均 elapsed  : %.1f ms\n", sum_e / n;
    printf "平均 Latency  : %.1f ms  (首字节)\n", sum_l / n;
    printf "平均 Connect  : %.1f ms\n", sum_c / n;
    printf "Connect>0 占比: %.1f%%  (%d/%d)\n", conn_nonzero * 100.0 / n, conn_nonzero, n;
    asort(e); asort(c);
    printf "elapsed  p50/p90/p99/max: %d / %d / %d / %d ms\n", e[int(n*0.5)], e[int(n*0.9)], e[int(n*0.99)], e[n];
    printf "Connect  p50/p90/p99/max: %d / %d / %d / %d ms\n", c[int(n*0.5)], c[int(n*0.9)], c[int(n*0.99)], c[n];
}
