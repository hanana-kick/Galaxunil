# Galaxunil

Galaxunil은 Android에서 Debian 13 앱을 찾고 설치하고 실행하는 미리보기 앱입니다. UI는 **네이티브 Jetpack Compose와 Material 3**로 구성되어 있습니다. Android 10 이상, ARM64 및 x86-64를 대상으로 합니다.

- **앱 스토어:** Debian AppStream 검색·분류, 서명된 APT 저장소에서 설치, 설치 상태 확인
- **앱 실행:** 패키지가 소유한 시스템 데스크톱 항목만 스토어에서 실행합니다. 첫 검증 대상은 Firefox ESR입니다.
- **그래픽:** Wayland 기반이며 앱별 VirGL·llvmpipe 설정을 제공합니다. 앱의 실행 호환성과 실기 GPU 가속은 별도 검증이 필요합니다.
- **별도 설치:** Galaxunil 패키지명은 `io.github.hananakick.galaxunil`입니다. 기존 Void 및 Debian 미리보기의 저장 공간을 변경하지 않습니다.

## 개발 빌드

JDK 21, Android SDK 플랫폼 **37.0**, Build Tools **37.0.0**, Python 3.11 이상, curl, dpkg-deb가 필요합니다. Android SDK 경로를 `ANDROID_HOME`으로 지정하세요.

```sh
python3 tools/bootstrap_assets.py
./gradlew :app:assemblePlayDebug :app:testPlayDebugUnitTest
```

부트스트랩은 고정된 DroidNative APK, Debian 공식 OCI 레이어와 Debian CA 패키지를 받아 SHA-256을 확인한 뒤 `build/galaxunil-assets/`에 재현합니다. 릴리스 APK에는 아카이브·TLS 검증을 끄는 옵션이나 에뮬레이터 전용 프록시가 포함되지 않습니다. 원본 바이너리는 Git에 넣지 않습니다. 준비된 캐시는 `python3 tools/bootstrap_assets.py --verify`로 네트워크 없이 확인할 수 있습니다. 이미 받은 원본을 사용하려면 `--help`를 참고하세요.

태그 빌드의 버전은 `-PgalaxunilVersionName=<버전>`, `-PgalaxunilVersionCode=<양의 정수>`로 지정할 수 있습니다. 개인 서명 키와 비밀번호는 커밋하지 않습니다.

## 구현 및 검증 범위

Android 에뮬레이터에서는 이 프로젝트의 기반이 된 Debian 미리보기 APK 설치와 Debian 루트 파일시스템 초기화를 확인했습니다. 해당 에뮬레이터의 직접 인터넷 연결은 DNS에서 실패했습니다. 격리 테스트에서는 Debian 서명 검증을 유지한 채 공식 저장소 데이터를 전달했습니다. Galaxunil에서 Firefox ESR 실행 및 갤럭시 DeX의 실기 GPU 가속을 완료했다고 주장하지 않습니다.

DroidNative beta-0.1 (`fa591010d6e33adbf5a79178b874fd4abdc58931`)에서 파생됐습니다. GPL 조건은 [COPYING](COPYING), 원본 문서는 [Upstream README](docs/UPSTREAM-README.md)를 참고하세요. Debian 입력 파일의 이력과 해시는 [runtime-lock.json](tools/runtime-lock.json)에 있습니다.

- [CI/CD 및 릴리스 서명](CI.md)
