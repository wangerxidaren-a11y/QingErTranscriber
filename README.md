# 清耳转录（QingEr Transcriber）

专为小米手机（arm64-v8a，适配小米 14 / 小米 17 等澎湃 OS 机型）打造的**零广告、纯开源、全离线**音视频转录工具。

## 核心能力
- **底层开源引擎**：基于 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + 阿里开源 [SenseVoiceSmall](https://github.com/FunAudioLLM/SenseVoice)（int8 量化版，自动识别中英粤日韩并自动加标点）。
- **多源一键导入**：支持直接粘贴或系统分享 **小宇宙单集链接**、**B 站视频链接（含 `b23.tv`）**、**播客 RSS/音频直链**，以及选取手机本地音视频文件。
- **国内高速下载模型**：首次打开点一次「一键下载离线模型」，自动优先从魔搭社区（ModelScope）拉取约 228 MB 模型文件，之后永久离线运行。
- **后台保活与导出**：内置前台服务与 WakeLock，支持锁屏转写超长播客，自动生成带时间戳的 Markdown 文稿并支持一键复制或导出分享。
