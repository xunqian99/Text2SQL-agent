#!/bin/bash
# Text2SQL Agent 云服务器一键自动化部署脚本
# 适用系统：Ubuntu 22.04 LTS / Debian
set -e

echo "=========================================================="
echo "      🚀 开始一键部署 Text2SQL 智能体系统 (Java 21)       "
echo "=========================================================="

# 1. 检查并安装 Java 21 运行环境
echo ">>> [1/4] 安装 Java 21 JRE 运行环境..."
apt update -y
apt install -y openjdk-21-jre-headless

# 2. 启动 PostgreSQL 16 (pgvector) 容器
echo ">>> [2/4] 启动 PostgreSQL 数据库容器..."
docker rm -f text2sql-postgres 2>/dev/null || true
docker run -d \
  --name text2sql-postgres \
  --restart always \
  -e POSTGRES_DB=olist \
  -e POSTGRES_USER=text2sql \
  -e POSTGRES_PASSWORD=text2sql \
  -p 5432:5432 \
  pgvector/pgvector:pg16

echo ">>> 等待数据库初始化完成..."
until docker exec text2sql-postgres pg_isready -U text2sql -d olist; do
  sleep 2
done

# 3. 导入 37 张表全量真实数据并配置权限
echo ">>> [3/4] 导入 Olist 37 张表及全量业务数据..."
if [ -f "/root/olist.dump" ]; then
  docker cp /root/olist.dump text2sql-postgres:/tmp/olist.dump
  docker exec text2sql-postgres pg_restore -U text2sql -d olist --clean --if-exists /tmp/olist.dump || true
else
  echo "警告：未找到 /root/olist.dump，跳过全量数据导入"
fi

echo ">>> 配置物理只读角色 text2sql_ro 与持久化角色 text2sql_rw..."
docker exec -i text2sql-postgres psql -U text2sql -d olist << 'EOF'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'text2sql_ro') THEN
        CREATE ROLE text2sql_ro LOGIN PASSWORD 'text2sql_ro';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'text2sql_rw') THEN
        CREATE ROLE text2sql_rw LOGIN PASSWORD 'text2sql_rw';
    END IF;
END $$;
GRANT CONNECT ON DATABASE olist TO text2sql_ro, text2sql_rw;
GRANT USAGE ON SCHEMA public TO text2sql_ro, text2sql_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO text2sql_ro;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO text2sql_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO text2sql_ro;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO text2sql_rw;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO text2sql_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO text2sql_rw;
EOF

# 4. 后台启动 Spring Boot 服务
echo ">>> [4/4] 启动 Spring Boot 后台服务..."
pkill -f text2sql-agent 2>/dev/null || true

# 读取 API Key（支持环境变量传入；若未设置则启动进入 BYOK 纯自带密钥模式）
API_KEY="${AGENT_LLM_API_KEY:-}"

if [ -n "$API_KEY" ]; then
  nohup java -Xms512m -Xmx1024m \
    -Dagent.llm.api-key="$API_KEY" \
    -jar /root/text2sql-agent-0.1.0-SNAPSHOT.jar > /root/app.log 2>&1 &
else
  nohup java -Xms512m -Xmx1024m \
    -jar /root/text2sql-agent-0.1.0-SNAPSHOT.jar > /root/app.log 2>&1 &
fi

echo ">>> 正在等待服务启动 (约 10 秒)..."
sleep 10

echo "=========================================================="
echo "🎉 部署完成！"
echo "服务已在后台运行，查看实时日志请敲：tail -f /root/app.log"
echo "请在浏览器打开：http://$(curl -s ifconfig.me):8080"
echo "=========================================================="
