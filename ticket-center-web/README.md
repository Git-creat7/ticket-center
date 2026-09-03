# Ticket Center Web

Ticket Center 用户端，基于 Vue 3、Vite 和 TypeScript。

## 本地启动

先启动 `ticket-center-api`，再从项目根目录执行：

```bash
cd ticket-center-web
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器会把 `/api` 请求代理到
`http://localhost:8080`，因此不需要修改后端 CORS。

## 构建

```bash
npm run build
```

活动海报已经提交到 `public/imgs/events/`。需要重新生成时运行：

```bash
npm run assets:generate
```
