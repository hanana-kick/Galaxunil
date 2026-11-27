# Live diagnostics
The switch is under Settings → Device diagnostics. It is off by default and does not survive app process death.
1. Open the [private inbox](https://galaxunil-device-debug.bbggkkk.chatgpt.site) and sign in as its owner.
2. Issue a four-digit code and enter it in the Galaxunil switch dialog within two minutes. Leading zeroes are valid. After three guesses or one successful use, issue a new code.
   The app exchanges this code for an in-memory append-only token; it never shows or stores the token. Streaming lasts up to 15 minutes.
3. Reproduce the failure. The dashboard checks for new app event types every three seconds while open.
4. Turn off the switch. The app cancels queued sends; an event already accepted may remain visible until the session is removed.
5. Use “연결 종료 및 기록 삭제” on the private page to revoke the code and delete the bounded record.
v0.1.3 also sends the app UID's Android log buffer and all available app-owned guest logs (including prior
setup output, catalog APT, desktop services, VirGL and Linux application logs). Raw logs may contain
paths, URLs and guest output. Other apps'/system-wide logs, screenshots, or camera and microphone data
are not collected. A separate exact-byte stream retains up to 16 MiB per live session without replacing
earlier chunks. Backfill is limited to what the device still has. The dashboard shows a preview and can
save all received chunks. A session becomes unreadable on expiry; its stored logs are deleted on the
next dashboard read, new code, or explicit close. No ADB listener or device-control channel is installed.
