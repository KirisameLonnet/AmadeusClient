# AmadeusClient 页面理解与 OCR 业务逻辑设计

## 1. 文档目的

本文用于统一下一阶段开发目标：在已具备 `Accessibility Node Tree` 主语义的基础上，补齐 OCR 文字语义能力，并保证整体链路可扩展到高频实时场景（目标上限 10Hz）。

文档覆盖：

1. 现有系统工作原理与全链路业务逻辑梳理。
2. OCR 能力接入原则、模块划分与数据流。
3. 性能目标、延迟预算、缓存与调度策略。
4. 开发分期、验收指标与风险控制。

---

## 2. 当前系统现状（已实现）

### 2.1 应用与服务结构

- `MainActivity`：UI 主入口，负责状态轮询、快照刷新、频率配置同步。
- `AmadeusAccessibilityService`：无障碍事件监听、UI 树抓取、快照构建、可选截图裁剪增强。
- `SnapshotBroadcasts`：进程内广播通道，承载 `snapshot/status/request` 三类信号。
- `HomeScreen`：快照可视化与控制项（显示马赛克、节点覆盖层、拉取频率）。
- `SettingsScreen`：服务开关入口、Vision Assist 应用名单配置。

### 2.2 核心数据输出

当前输出的 `ui_state` 主体为：

- 页面级信息：`package_name`、`activity_name`、`event_type`、`timestamp`。
- 节点级信息：`id/parent/depth/index/bounds/class/resource/text/desc/交互属性`。
- 可选视觉片段：`vision_segments[]`，每项包含 `id + bounds + image_base64`。

### 2.3 当前视觉增强能力（已存在）

当目标 App 被配置为 Vision Assist 时，服务会：

1. 从节点树中提取候选视觉目标（优先无文字但可交互/视觉型节点）。
2. 对应用窗口截图（Android 版本能力允许时）。
3. 按节点 `bounds` 裁剪、压缩为 JPEG，写入 `vision_segments`。

这为 OCR 接入提供了天然输入基础：我们已具备“结构树 + 对应 crop”的映射关系。

---

## 3. 端到端业务链路梳理

## 3.1 启动与状态同步

1. `MainActivity.onCreate` 初始化 Compose，并读取频率配置。
2. `onResume` 注册广播接收器，启动定时状态刷新。
3. 定时任务触发 `refreshStatus`：
   - 同步拉取频率配置。
   - 更新服务启用态。
   - 读取最近快照。
   - 若服务已启用且快照为空，则发起主动快照请求。

## 3.2 快照采集与发布

1. 无障碍事件到达 `AmadeusAccessibilityService.onAccessibilityEvent`。
2. 经过事件类型过滤、包名过滤、防抖频控后，开始构建快照。
3. 遍历 `rootInActiveWindow` 生成 `elements`。
4. 计算快照语义签名，进行重复帧抑制。
5. 若目标包名启用 Vision Assist，则尝试截图并生成 `vision_segments`。
6. 调用 `SnapshotBroadcasts.publishSnapshot` 发送更新广播。

## 3.3 前台预览链路

1. `MainActivity` 收到 `ACTION_SNAPSHOT_UPDATED` 后更新状态流。
2. `HomeScreen` 将原始 JSON 交给 `UiStateParser` 解析。
3. `UiStatePreviewView` 根据节点和视觉片段绘制预览。

## 3.4 配置链路

- 频率配置：`PreviewControlsConfig` 负责上下限、步进、持久化。
- Vision Assist 白名单：`VisionAssistConfig` 负责每个包名是否启用视觉增强。
- 应用筛选能力：支持系统应用显示开关、排序、搜索。

---

## 4. 为什么需要 OCR（业务与技术动机）

虽然主语义已由 Accessibility 提供，但仍存在缺口：

1. 自绘控件、Canvas、WebView 子区域文本常无法直接出现在 `text/desc`。
2. 某些按钮/标签无可访问性文案，只能从像素中恢复文本。
3. 仅依赖整图多模态会造成 token 成本高、时延不可控。

因此需要加入 OCR 作为“结构树补洞层”，而不是替代结构树。

---

## 5. 目标架构（结构树优先 + OCR 增强）

总体原则：

- 主语义来源：`Accessibility Node Tree`
- OCR 作用：补齐缺失文字
- 视觉模型（后续可选）：补齐图标/状态语义

建议流水线：

1. **Frame 构建**：采集 `ui_state` + 可选 `vision_segments`。
2. **OCR 候选筛选（CPU）**：基于“排除原则”过滤显然无价值节点。
3. **OCR 调度执行**：根据预算、优先级、缓存命中异步执行。
4. **结果回填**：将 OCR 文本绑定到对应 `node_id`。
5. **语义融合输出**：产出结构化页面语义供上层决策使用。

---

## 6. OCR 候选筛选设计（排除原则）

> 本阶段不追求筛得“极少”，而是避免误杀，先保证召回。

### 6.1 输入

- 节点属性：`text/desc/class/resource/bounds/child_count/交互属性/可见性`
- 视觉片段：`vision_segments`（若存在）

### 6.2 仅排除明确低价值节点

可直接排除的典型条件：

1. `is_visible_to_user == false`
2. 已有有效 `text` 或 `desc`
3. 尺寸极小（图标点位级）
4. 纯容器且不可交互，且面积接近整屏背景
5. 与父节点高度重合、明显仅作为包裹层
6. 缓存命中且区域内容未变化

### 6.3 候选保留策略

以下情况默认保留，进入 OCR 队列：

1. 可点击/可编辑/可聚焦但无文本。
2. 类名或资源名可能承载文本语义（如 button/title/label/item）。
3. 中等面积且长宽比像按钮、标签、列表项。
4. 自定义 `View` 或 WebView 局部区域。

### 6.4 父子去重与覆盖策略

- 采用“父子互斥 + 低重复优先”：避免同一区域多次 OCR。
- 当父节点已进入 OCR 且覆盖范围几乎包含子节点时，跳过子节点。
- 当父节点是明显容器而子节点更像文本载体时，优先子节点。

---

## 7. OCR 执行与调度策略

### 7.1 执行原则

1. OCR 异步执行，不阻塞主快照链路。
2. 每帧限制任务预算，防止长尾堆积。
3. 新增/变化节点优先，静态节点依赖缓存复用。

### 7.2 建议队列模型

- `P0`：输入框、主按钮、关键交互位（最高优先）。
- `P1`：普通无文本交互节点。
- `P2`：弱相关可疑节点（空闲补偿）。

调度规则：

1. 每帧先消费 `P0`，再消费 `P1`，最后 `P2`。
2. 达到帧预算后停止，剩余任务延期到下一帧。
3. 同 key 任务合并去重（避免重复提交 OCR）。

### 7.3 缓存策略

建议缓存 key：

`package + activity + node_id + bounds + image_hash`

缓存命中时直接复用历史 OCR 结果，跳过推理。

---

## 8. 性能目标与预算

## 8.1 目标定义

- 上限目标：`10Hz`（200ms/帧）
- 目标分解：
  - 筛选阶段可稳定在低耗时（通常远低于 200ms）
  - OCR 阶段采用预算与异步策略，不要求“每帧全量 OCR 完成”

## 8.2 建议预算（参考）

- 节点筛选（CPU）：`5~20ms`
- 去重/缓存查询：`<10ms`
- OCR 执行：按帧预算动态截断（如 `80~150ms`）
- 结果融合与序列化：`<20ms`

关键认知：

`10Hz` 的核心是“每帧都能完成结构更新与候选筛选”，而不是“每帧完成所有候选 OCR”。

---

## 9. 结果数据结构（建议）

建议在原有节点上增加 OCR 扩展字段：

- `ocr_text`: OCR 输出文本
- `ocr_confidence`: 置信度
- `ocr_source`: 数据来源（`ocr/cache/accessibility`）
- `ocr_timestamp`: 结果时间戳

融合优先级建议：

1. 优先 `accessibility text/desc`
2. 若为空，再使用 `ocr_text`
3. 若仍为空，保留视觉模型或规则推断字段（后续阶段）

---

## 10. 异常与降级策略

1. 截图能力不可用：仅输出结构树，不阻塞主链路。
2. OCR 引擎失败：记录错误并降级到缓存或空结果。
3. 队列积压：降低 `P2` 处理频率，保证 `P0/P1` 时效。
4. 高频滚动场景：仅识别新增区域，已识别区域复用缓存。

---

## 11. 监控与验收指标

### 11.1 性能指标

- 每帧筛选耗时（P50/P90/P99）
- OCR 单任务耗时与队列长度
- 端到端语义可用延迟（从节点出现到文本可用）

### 11.2 质量指标

- 文字召回率（有文字区域被识别覆盖的比例）
- 误识别率（无文字区域识别出噪声文本）
- 重复识别率（同一区域重复 OCR 的比例）

### 11.3 稳定性指标

- OCR 失败率
- 超时率
- 降级触发频率

---

## 12. 分期实施计划

### Phase 1：OCR 基础落地

1. 建立“排除原则”筛选器（宽松召回）。
2. 打通 OCR 异步队列与结果回填。
3. 建立基础缓存与去重。

验收：可稳定补齐主要无障碍缺失文本，主链路无明显卡顿。

### Phase 2：性能收敛

1. 引入优先级队列与帧预算调度。
2. 增加变化检测，减少重复 OCR。
3. 完善指标与压测脚本。

验收：高频操作场景下时延稳定，队列无长期堆积。

### Phase 3：语义增强（可选）

1. 引入图标/状态识别（非文字语义）。
2. 完善融合策略，形成统一页面语义输出。

验收：在低可访问性页面下语义完整性明显提升。

---

## 13. 与当前代码映射关系（便于实施）

- 快照与节点生成：`app/src/main/java/com/astramadeus/client/AmadeusAccessibilityService.kt`
- 频率配置：`app/src/main/java/com/astramadeus/client/PreviewControlsConfig.kt`
- 广播总线：`app/src/main/java/com/astramadeus/client/SnapshotBroadcasts.kt`
- UI 状态轮询：`app/src/main/java/com/astramadeus/client/MainActivity.kt`
- Vision Assist 应用名单：`app/src/main/java/com/astramadeus/client/VisionAssistConfig.kt`
- 预览解析与渲染：
  - `app/src/main/java/com/astramadeus/client/UiStateParser.kt`
  - `app/src/main/java/com/astramadeus/client/UiStatePreviewView.kt`

---

## 14. 决策结论

1. 保持“结构树优先”路线不变。
2. OCR 作为补洞层，按排除原则进行候选筛选。
3. 使用异步队列、预算调度、缓存去重保证实时性。
4. `10Hz` 目标可达前提是：每帧完成筛选与主链路更新，OCR 允许跨帧回填。

该方案兼顾工程可落地性、性能可控性与语义完整性，适合作为当前版本的正式开发蓝图。
