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
6. Vision Assist 应用名单：可按应用开启截图增强链路。
7. 视觉增强流水线：`UI tree -> screenshot -> crop -> OCR -> merge`。
8. OCR 文本回填到节点，并在预览层显示 OCR overlay。
9. WebSocket 推送：将精简后的 `ui_frame_full` JSON 帧推送到桌面端/服务端。
10. WebSocket 设置页：支持配置 WS 地址、开关推送、查看本机网卡地址。
11. OCR pipeline 设置页：支持调节 OCR 并发度。
12. 传输帧精简：不传图片，仅传结构节点、文本、点击点、颜色摘要与语义提示。
13. 轻量语义提示：对部分高价值 icon/button 推断 `search/back/more/cart/message/home/profile` 等 `semantic_hint`。

## 工作原理（简版）

1. 客户端监听无障碍事件。
2. 读取当前窗口节点树并生成基础 `ui_state`。
3. 对低可访问性区域执行截图裁剪与 OCR，补齐缺失文本。
4. 将 OCR 结果、颜色信息与节点结构合并，生成精简后的传输帧。
5. 通过 WebSocket 将页面帧发送给上游 AstrBot / 插件侧服务端。
6. 后续由 AstrBot 回传动作指令，客户端负责执行。

## 当前帧特性

当前 WebSocket 推送帧具备以下特点：

1. 以 `ui_frame_full` 作为统一消息类型。
2. 保留页面层级、位置、交互属性、点击点。
3. 节点文本已合并为 `text + text_source`，避免 `text/desc/ocr_text` 三字段并存。
4. OCR 中间图片不会进入传输帧。
5. 对部分 OCR 节点补充 `avg_color`，用于辅助判断视觉语义。
6. 对部分无文字 icon/button 补充 `semantic_hint`。

## 下一步计划

1. 继续提升传输帧的信息密度，减少低价值容器节点与重复语义。
2. 完成动作执行闭环（`click`、`swipe`、`input_text`、`back`、`home`）。
3. 增强黑盒/低可访问性页面中的 icon 语义识别与结构理解。
4. 逐步引入增量帧与更稳定的动作/状态同步协议。
