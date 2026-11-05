# 自有武器关系开发样本

013五类开发样本均有INI/JSON/Hjson：mounted、linked-forward、linked-reverse、unlinked、linked-disabled，复用现有自有地图/占位图片，不是新原版兵种或原版数值。HP100000/global range450/sight30等为固定开发参数。输入/独立契约在实际消费前写定。fallback根仍旧单武器；原版字节不在本目录。

mounted父base只瞄准远敌c，附属独立炮打b，slave随父打c，父不产生弹体。延迟样本leader选c、follower选b，leader每30步发弹；普通warmup3使forward follower为第4/34/64/94步，reverse为4/35/65/95；无link仅第4步一次，禁止射击对照不产生follower弹体。这个固定布局的有限算例不证明原版全局调度，也不将sim-tick解释为秒。

机制和同Runner轨迹已有本票定向验证，公共隔离/原生画面资格仍须看对应票据实测。Hjson包含注释与未引号键；三格式共享基/节模板，玩法内容一致。契约在platform/scenarios及contracts下以weapon-relations-前缀配对，完整出生时序和零发射对照还由共享Runner测试读原轨迹核对，不能仅凭文件存在或主炮标量判通过。
