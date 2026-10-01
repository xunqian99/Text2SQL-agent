# 原始数据获取方式

这个目录**不纳入版本控制**，因为原始数据集体积过大
（Olist CSV 约 170MB，`bird_minidev.zip` 约 800MB），
且属于第三方公开数据，直接提交到仓库既超限也没有意义。

`scripts/rebuild_db.ps1` 依赖下面两个文件，clone 仓库后需要自行下载：

## 1. Olist 巴西电商数据集

来源：<https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce>

下载后解压，把 9 个 CSV 放到 `data/raw/olist/`，文件名保持原样：

```
data/raw/olist/
├── olist_customers_dataset.csv
├── olist_geolocation_dataset.csv
├── olist_order_items_dataset.csv
├── olist_order_payments_dataset.csv
├── olist_order_reviews_dataset.csv
├── olist_orders_dataset.csv
├── olist_products_dataset.csv
├── olist_sellers_dataset.csv
└── product_category_name_translation.csv
```

这些文件名被 `data/schema/02_load_olist.sql` 里的 `COPY` 语句直接引用，
改名会导致导入失败。

## 2. BIRD Mini-Dev（可选，外部参照系）

来源：<https://bird-bench.github.io/>

文件：`bird_minidev.zip`，放在 `data/raw/` 下。

这个数据集**当前未被任何脚本使用**，是 ROADMAP 5.4 节「外部参照系」
预留的素材——用于在公开 benchmark 上跑一遍，和已发表 baseline 对比，
避免准确率数字「自说自话」。阶段 1 不涉及。

## 下载完成后

```powershell
# 重建数据库（建表 + 导入 + 扩展 + 造数）
powershell -File scripts/rebuild_db.ps1

# 核对行数
Get-Content scripts/check_counts.sql | docker exec -i text2sql-postgres psql -U text2sql -d olist
```
