# 来袭弹布置场景

这两个自有内容包用于启用 Sandbox deployment 的离线开发对局。Windows 和无头 Session 共用地图、单位定义和场景中的弹体条目。

- [普通炮弹](../../platform/scenarios/deployment-shell-defense.json) 从两个在场单位布置普通弹和即时命中对照。普通弹实际飞入点防御范围并耗用能量。即时弹走实际命中，点防御不会拦截它。
- [地面反核](../../platform/scenarios/deployment-nuclear-defense.json) 先从正常动作列表支付 4000 备弹，再从已部署发射井布置真实核弹。拦截井检测到合格的来袭弹后消耗一枚反核弹。

Windows MCP 接受 `client_deploy_projectiles`。条目包含唯一 `alias`、在场 `sourceUnitId` 与 `targetUnitId`，可选 `weapon` 指定武器名。请求须带当前对局代次和 `requestId`。同一批次重试时复用该 ID，改过的批次使用新 ID。以 `client_deployment_receipt` 回读执行步、结果和别名对应的弹体 ID。无头 Session 通过 `deploy-projectiles` 意图提交同样的条目。
