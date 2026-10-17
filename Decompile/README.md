# 独立反编译代码与构建入口

本目录用于自有定点反编译辅助、原始符号定位与语义核验工具的源码和构建配置。不是原版输入目录，也不承诺重建完整可编译原版。

唯一落位规则见[三线代码与构建落位](../Docs/当前决策/三线代码与构建落位.md)。任务见[独立反编译开发线](../.scratch/开发票据/独立反编译/README.md)，研究仍在[Research](../Docs/Research/README.md)。

当前包含 [DEC 只读归档复核工具](<tools/verify_dec_evidence.py>)及其[隔离测试](<tools/test_verify_dec_evidence.py>)，尚无完整反编译工程或原版运行工具。Python 3.11+ 从项目根执行 `python -B Decompile/tools/verify_dec_evidence.py`；覆盖归档身份、迁移/维护记录与导航，不执行采集器、不写回旧结果、不证明机制语义。原始证据按所属票据/研究的既有路径归档，不放入源码或自动分发。反编译线最终成果汇报单独放在 [Research-DEC](<../Docs/Research-DEC/README.md>)，不搬入底稿、脚本或日志。
