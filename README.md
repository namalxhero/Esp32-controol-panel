# ESP32 OLED Controller

ESP32 එකට physical OLED display එකක් wire කරන්නේ නැතුව, phone app එකම OLED display එක ලෙස act කරන Android app එකක්. USB OTG cable එකෙන් ESP32 connect කරලා:

- **OLED View tab** — ESP32 එකෙන් එවන 128x64 pixel framebuffer එක exact ලෙස render කරනවා (real SSD1306 layout එකම).
- **Terminal tab** — plain text logs පේන්නවා, සහ ESP32 එකෙන් report කරන commands (`#CMDS:`) විතරක් chip buttons විදියට පෙන්නනවා — ඒවා tap කරලා හෝ manually type කරලා send කරන්න පුළුවන්.

## Protocol (ESP32 ↔ App)

Serial, 115200 baud. එක line එකක් = එක message එකක්:

```
#OLED:<base64 of 1024 bytes>     -> full frame (8 pages x 128 cols, SSD1306 layout)
#CMDS:LED_ON,LED_OFF,STATUS      -> available commands (app chips update automatically)
anything else                    -> plain log line
```

App එකෙන් ESP32 එකට යවන commands, plain newline-terminated text විදියට යනවා — ESP32 side එකේ `Serial.readStringUntil('\n')` කරලා parse කරන්න.

`esp32-firmware/esp32_oled_controller.ino` file එකේ working example එකක් තියෙනවා (Adafruit_GFX/SSD1306 libraries in-memory framebuffer එකක් විදියට පාවිච්චි කරලා, physical display init කරන්නේ නෑ).

## Build via Termux + GitHub Actions (PC/Android Studio නැතුව)

```bash
# 1. Termux එකේ repo එක clone කරගන්න (හෝ මේ zip එකේ files push කරන්න)
cd ~
git clone https://github.com/namalxhero/esp32-oled-controller.git
cd esp32-oled-controller

# 2. zip එකේ files ටික copy කරලා commit කරන්න
git add .
git commit -m "Initial ESP32 OLED Controller app"
git push origin main
```

Push කරාට පස්සේ **GitHub Actions** automatic run වෙලා `app-debug.apk` එක build කරනවා. Repo එකේ **Actions** tab එකට ගිහින්, latest run එකේ **Artifacts** section එකෙන් APK download කරගන්න පුළුවන් — ඒ වගේම **Releases** tab එකටත් auto-upload වෙනවා.

## OTG Connection

- USB OTG adapter/cable එකෙන් ESP32 board එක phone එකට connect කරන්න.
- App එක open කරලා **Connect** button එක press කරන්න → Android USB permission dialog එකක් එයි → Allow කරන්න.
- CP2102, CH340/CH341, FTDI, සහ native ESP32-S2/S3/C3 USB-CDC chips support කරනවා (`res/xml/device_filter.xml` එකේ vendor/product IDs check කරන්න).
- Cable එක attach කරාම app එක automatic ලෙසත් launch වෙයි (USB_DEVICE_ATTACHED intent-filter එක හින්දා).

## Customizing

- Command chips වෙනස් කරන්න ESP32 firmware එකේ `SUPPORTED_COMMANDS` array එක edit කරන්න — app එකට extra code වෙනසක් අවශ්‍ය නෑ, `#CMDS:` line එකෙන් auto-update වෙනවා.
- OLED colors, dark theme — `app/src/main/res/values/colors.xml`.
- Baud rate වෙනස් කරන්න ඕනේනම් `UsbSerialManager.kt` එකේ `BAUD_RATE` constant එක.
