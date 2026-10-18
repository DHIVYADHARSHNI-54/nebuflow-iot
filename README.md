# NebuFlow-IoT website + hardware integration

- `server/`   Java 17+ web server + dashboard (open this folder in VS Code, press F5)
- `firmware/` ESP32 code (open this folder in VS Code with the PlatformIO extension)

Test without hardware: run the "DEMO" launch configuration, open http://localhost:8080
Real hardware: set WIFI_SSID / WIFI_PASS / SERVER_URL in firmware/src/main.cpp, upload, run the server normally.
