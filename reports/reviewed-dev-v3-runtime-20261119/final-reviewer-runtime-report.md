# 开发参考运行兼容性独立接受

最终接受：100 PASS。99题全部YAML元素完全相同；T5-019仅Gold的CTE声明及对应引用从orders改为order_counts。100题题面、标签、难度、备注、输出列orders均未改。

独立只读执行原SQL与运行兼容SQL各返回20行，顺序与数值相同；双向EXCEPT ALL分别0行，耗时0.469秒。未重复全100执行，模型/供应商调用0次。

原冻结六文件仍与上次独立接受SHA相符，生产契约和源DDL SHA未变。原报告与原失败记录未改。本次只验精确重命名及数据库等价，不冒充另一个Java校验器预检。

## 新六YAML SHA-256

| 文件 | SHA-256 |
|---|---|
| data/benchmarks/olist-v3-dev-runtime-candidate/T1_single_table_basic.yaml | 6650bdadd9e150340e93e19b0fb1e6f7f6c3abca68633f18d207b02329348e8e |
| data/benchmarks/olist-v3-dev-runtime-candidate/T2_single_table_agg.yaml | 70a43a2709126ed438c125b8b0c2aee9c855df27f5371adb1fc08f63215fac57 |
| data/benchmarks/olist-v3-dev-runtime-candidate/T3_time_window.yaml | 3dda45b41dc067ac4fcc8d817173cc14e0ea5381c31265c07a8fa8f146e993ed |
| data/benchmarks/olist-v3-dev-runtime-candidate/T4_multi_join.yaml | 0143ed484566df7f84436af9cdab4c615f9ce17f6a009d2db32783c13efa6397 |
| data/benchmarks/olist-v3-dev-runtime-candidate/T5_multi_join_deep.yaml | 84c2b6b827a11bfb21357301bc462a36d0b3fd9a4db744bccb37f09ea2318782 |
| data/benchmarks/olist-v3-dev-runtime-candidate/T6_business_semantics.yaml | 1f838f0066c71721ce49e0c8d7ed18aa44acd717d4291933f74fbe3a41f4935b |

候选manifest SHA-256：`c341eb5969185042fe7823b84fa9318ca9e0e68032b66ecf3b47ba560ebcb77c`。
生产契约与DDL manifest SHA-256：`2e56e838db3caa6b413815c4af876941a9121cb02f5e1a7715460d93ba3c3fa4`。

完整100项差异核对、逐题内容SHA、来源清单及执行证据见 `final-reviewer-runtime-acceptance.json`。
