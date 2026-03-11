# AmadeusClient

AmadeusClient 是 AstrBot 用于控制安卓手机的客户端应用。

## 项目状态

本项目仍在持续开发中。

## 项目定位

AmadeusClient 运行在安卓物理机上，核心职责是执行设备侧能力，不承担复杂推理。

主要分为两部分：

1. 感知（Perception）：通过 Android 无障碍服务获取当前页面的 UI 状态。
2. 执行（Action）：接收 AstrBot 下发的动作并在手机上执行。

## 当前已实现

当前版本已完成以下能力：

1. 无障碍服务注册与启用链路。
2. 前台活跃窗口 UI 树实时抓取。
3. UI 快照序列化为结构化 `ui_state` JSON。
4. 基础过滤与去重，减少重复噪声。
5. 应用内可视化预览：按 `ui_state` 信息渲染页面结构示意图。

## 工作原理（简版）

1. 客户端监听无障碍事件。
2. 读取当前窗口节点树并生成 `ui_state`。
3. 该 `ui_state` 作为 AstrBot 的页面感知输入。
4. 后续由 AstrBot 回传动作指令，客户端负责执行。

## 下一步计划

1. 对接 WebSocket 通信链路。
2. 完成动作执行闭环（`click`、`swipe`、`input_text`、`back`、`home`）。
3. 优化黑盒/低可访问性页面的识别与处理策略。
