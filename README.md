# LexDesk

A private, legal-only AI assistant for Android with three engines:

- **On-device (Gemma 4, via Google LiteRT-LM):** runs offline on the phone; nothing leaves the device.
- **Gemini (free tier):** cloud, reads PDFs and photos; Google may use free-tier content to improve its products.
- **Claude (optional, paid).**

**Auto** mode (default) uses Gemini when online and the on-device model when offline or if Gemini fails.
The **lock** button in any chat turns on Private mode, which keeps that chat on the phone.

## Build and install

1. Open the `LexDesk` folder in Android Studio and let Gradle sync. Decline any AGP upgrade prompt.
2. Select your phone in the device list and press Run.
3. In the app's Settings:
   - paste a free Gemini key from aistudio.google.com (Get API key);
   - under On-device model, choose E4B (or E2B) and tap Download, on Wi-Fi.

## Where to change things

- Scope and drafting rules: `LegalPrompt.kt`
- Engine routing and fallback: `ChatViewModel.kt` (`chooseEngine`)
- On-device engine and prompt packing: `ondevice/`
- Model list and download links: `ondevice/ModelManager.kt`
- Gemini and Claude clients: `api/`
- Word export formatting: `util/DocxExporter.kt`
