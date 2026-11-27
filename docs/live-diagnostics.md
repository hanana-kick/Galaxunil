# Live diagnostics
The switch is under Settings → Device diagnostics. It is off by default and does not survive app process death.
1. Open the [private inbox](https://galaxunil-device-debug.bbggkkk.chatgpt.site) and sign in as its owner.
2. Issue a 15-minute code and enter it in the Galaxunil switch dialog.
3. Reproduce the failure. The dashboard checks for new app event types every three seconds while open.
4. Turn off the switch. The app cancels queued sends; an event already accepted may remain visible until the session is removed.
5. Use “연결 종료 및 기록 삭제” on the private page to revoke the code and delete the bounded record.
Only fixed event types and sequence numbers are sent. The app never submits Android logcat, raw stdout, file paths, URLs,
screenshots or identifiers in this mode. No ADB listener or device-control channel is installed.
