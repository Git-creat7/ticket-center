# 单阶段构建：vite preview 托管构建产物时仍需要 vite 本体，
# 而 vite 在 devDependencies 里，裁掉 node_modules 就起不来。
FROM node:24-alpine
WORKDIR /app

# 先只拷清单，利用 Docker 层缓存：依赖没变时跳过重新安装
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

EXPOSE 5173
CMD ["npm", "run", "preview"]
