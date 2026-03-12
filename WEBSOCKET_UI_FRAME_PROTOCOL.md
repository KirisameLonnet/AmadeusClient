# WebSocket UI Frame Protocol (Inference Friendly)

## 1. 目标

本协议用于在 `AmadeusClient -> AI` 之间传输页面理解帧，优先保证：

1. 推理友好：让模型快速建立页面心智模型。
2. 可执行：每个可交互元素都能直接落到点击坐标。
3. 可增量：支持 Full Frame + Delta Frame，降低端到端延迟。

本版仅定义推理友好格式，不提供带宽极限压缩版。

---

## 2. 连接与消息封装

## 2.1 WebSocket 连接

- URL: `wss://<server>/ws/ui`
- 鉴权: `Authorization: Bearer <token>` 或 query token
- 压缩: 建议开启 permessage-deflate

## 2.2 统一消息信封

所有消息都使用以下外层结构：

```json
{
  "type": "ui_frame_full",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 1024,
  "sent_at_ms": 1773312000123,
  "payload": {}
}
```

字段说明：

- `type`: 消息类型
- `protocol_version`: 协议版本，当前 `1.0`
- `session_id`: 同一设备会话唯一 id
- `seq`: 单调递增序号（会话内）
- `sent_at_ms`: 发送时间
- `payload`: 业务内容

---

## 3. 消息类型

## 3.1 `hello`（连接建立）

客户端连上后立即发送：

```json
{
  "type": "hello",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 1,
  "sent_at_ms": 1773312000000,
  "payload": {
    "device": {
      "model": "OnePlus-CPH2447",
      "android_version": "14",
      "width_px": 1440,
      "height_px": 3168,
      "density": 3.5
    },
    "client": {
      "app_version": "1.0.0",
      "build": "debug"
    },
    "capabilities": {
      "accessibility": true,
      "vision_segments": true,
      "ocr": true,
      "delta_frame": true
    }
  }
}
```

## 3.2 `ui_frame_full`（全量帧）

用于首帧或场景切换、增量失配后的重同步。

```json
{
  "type": "ui_frame_full",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 100,
  "sent_at_ms": 1773312001123,
  "payload": {
    "frame_meta": {
      "frame_id": "f_100",
      "timestamp_ms": 1773312001099,
      "package": "me.ele",
      "activity": "MainActivity",
      "orientation": "portrait",
      "screen_px": [1440, 3168]
    },
    "scene_summary": {
      "page_type": "food_list",
      "title": "德克士",
      "primary_actions": [
        { "action_id": "a_search", "label": "搜索", "target_node_id": "n_search" },
        { "action_id": "a_open_store_1", "label": "进入店铺", "target_node_id": "n_store_1" }
      ],
      "important_texts": ["搜索", "学生特价", "¥39.8"]
    },
    "reading_order": ["n_header", "n_search", "n_banner", "n_store_1", "n_price_1", "n_tab_home"],
    "nodes": [
      {
        "id": "n_search",
        "parent_id": "n_header",
        "children_ids": [],
        "depth": 1,
        "role": "input",
        "visible": true,
        "clickable": true,
        "bbox_px": [520, 320, 1408, 464],
        "bbox_norm": [0.361, 0.101, 0.978, 0.146],
        "tap_point_px": [964, 392],
        "text": {
          "accessibility": "",
          "ocr": "搜索",
          "final": "搜索",
          "source": "ocr",
          "confidence": 0.95
        },
        "attrs": {
          "resource_id": "com.xxx:id/search",
          "class_name": "android.view.View",
          "is_scrollable": false,
          "is_editable": false
        }
      }
    ],
    "actions": [
      {
        "action_type": "tap",
        "target_node_id": "n_search",
        "tap_point_px": [964, 392],
        "confidence": 0.97
      }
    ],
    "text_index": [
      { "node_id": "n_search", "text": "搜索" },
      { "node_id": "n_price_1", "text": "¥39.8" }
    ],
    "debug": {
      "vision_segments_total": 48,
      "ocr_hits": 35,
      "ocr_misses": 13,
      "pipeline_cost_ms": 182
    }
  }
}
```

## 3.3 `ui_frame_delta`（增量帧）

在已有基准帧上只传变化，降低 AI 重复阅读成本。

```json
{
  "type": "ui_frame_delta",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 101,
  "sent_at_ms": 1773312001220,
  "payload": {
    "frame_meta": {
      "frame_id": "f_101",
      "base_frame_id": "f_100",
      "timestamp_ms": 1773312001210,
      "package": "me.ele",
      "activity": "MainActivity"
    },
    "delta": {
      "added_nodes": [
        {
          "id": "n_toast_1",
          "parent_id": null,
          "children_ids": [],
          "depth": 0,
          "role": "toast",
          "visible": true,
          "clickable": false,
          "bbox_px": [120, 2680, 1320, 2780],
          "bbox_norm": [0.083, 0.846, 0.917, 0.878],
          "text": {
            "accessibility": "",
            "ocr": "已加入购物车",
            "final": "已加入购物车",
            "source": "ocr",
            "confidence": 0.93
          },
          "attrs": {
            "resource_id": "",
            "class_name": "android.view.View"
          }
        }
      ],
      "updated_nodes": [
        {
          "id": "n_cart_badge",
          "patch": {
            "text.final": "2",
            "text.ocr": "2",
            "text.source": "ocr"
          }
        }
      ],
      "removed_node_ids": ["n_toast_old"],
      "updated_reading_order": ["n_header", "n_search", "n_store_1", "n_toast_1", "n_tab_cart"]
    },
    "scene_summary_patch": {
      "important_texts": ["已加入购物车", "购物车(2)"]
    }
  }
}
```

## 3.4 `frame_ack`（服务端确认）

```json
{
  "type": "frame_ack",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 10001,
  "sent_at_ms": 1773312001250,
  "payload": {
    "acked_frame_id": "f_101",
    "status": "ok"
  }
}
```

## 3.5 `client_action`（服务端下发动作）

```json
{
  "type": "client_action",
  "protocol_version": "1.0",
  "session_id": "sess_abc123",
  "seq": 10002,
  "sent_at_ms": 1773312001300,
  "payload": {
    "action_id": "act_7788",
    "action_type": "tap",
    "target": {
      "node_id": "n_search",
      "fallback_tap_point_px": [964, 392]
    },
    "constraints": {
      "must_match_frame_id": "f_101",
      "timeout_ms": 1500
    }
  }
}
```

---

## 4. 推理友好设计原则

1. **摘要优先**：`scene_summary` 放在前面，先给模型主题与主操作。
2. **阅读顺序显式化**：`reading_order` 降低模型重排页面成本。
3. **文本融合统一出口**：只要求模型读 `text.final`，同时保留来源可追溯。
4. **动作可直接执行**：`actions`/`tap_point_px` 让模型无需二次几何推断。
5. **层级+几何并存**：`parent_id/children_ids/depth` + `bbox` 保证逻辑结构与视觉结构同时可用。

---

## 5. 建议的 AI 解析顺序

建议服务端提示模型按以下步骤读取单帧：

1. 读 `scene_summary` 判断页面类型和目标。
2. 读 `primary_actions` 形成可执行候选。
3. 读 `reading_order` 获取页面叙事流。
4. 必要时下钻 `nodes` 定位元素并确认坐标。
5. 使用 `actions` 直接生成执行指令。

这套顺序能显著降低模型在大节点树中的搜索成本。

---

## 6. 与当前实现对齐建议

基于当前项目，建议映射如下：

- `frame_meta.package/activity` <- `ui_state.data.package_name/activity_name`
- `nodes[*].attrs` <- 当前节点 JSON 字段
- `nodes[*].text.ocr` <- OCR 识别结果（按 `node_id` 回填）
- `text.final` <- `accessibility` 优先，缺失时用 `ocr`
- `actions` <- 根据 `clickable + bbox` 生成 `tap_point_px`

后续如加入图标语义识别，可扩展字段：`nodes[*].semantic.icon`、`nodes[*].state`。
