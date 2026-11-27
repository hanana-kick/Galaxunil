# Android 빌드와 배포

## 로컬 빌드

필요 도구: JDK 21, Python 3, `dpkg-deb`, Android SDK 플랫폼 37과 Build Tools 37.0.0. SDK는 `ANDROID_HOME` 또는 Android Studio의 `local.properties`로 지정합니다.

```sh
python3 tools/bootstrap_assets.py
python3 -m unittest discover -s tests -p test_debian_catalog.py
./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug
```

Python 테스트에는 PyYAML 6.0.2를 설치합니다. 자산 생성은 고정 SHA-256을 검증하며, 생성된 루트 파일시스템과 네이티브 라이브러리는 Git에 커밋하지 않습니다. 미리보기 APK는 디버그 키로 서명되며 서로 다른 환경에서 빌드한 미리보기 간에는 덮어설치가 보장되지 않습니다.

## GitHub Actions

- `main` 푸시, PR, 수동 실행: AppStream 파서, Android 단위 테스트, 서명 검증을 거친 미리보기 APK와 SHA256SUMS를 빌드 아티팩트로 올립니다.
- `main` 커밋에 붙인 `vMAJOR.MINOR.PATCH` (예: `v0.1.0` / `v0.1.0-rc.1`) 태그: 위 검증을 먼저 통과한 뒤 릴리스 APK를 빌드·서명합니다. GitHub Release 게시 작업에는 서명 키를 전달하지 않습니다.
- 이 파이프라인은 Google Play 게시나 실제 Android 게스트에서의 앱 설치·GPU 검증까지 수행하지 않습니다.

### 릴리스 키 설정

Settings → Secrets and variables → Actions에 다음 저장소 시크릿을 등록합니다. 키 파일이나 비밀번호를 저장소에 올리지 않습니다.

| 이름 | 값 |
| --- | --- |
| `GALAXUNIL_KEYSTORE_BASE64` | 별도로 보관한 배포용 keystore의 Base64 |
| `GALAXUNIL_KEY_ALIAS` | 서명 키 별칭 |
| `GALAXUNIL_STORE_PASSWORD` | keystore 비밀번호 |
| `GALAXUNIL_KEY_PASSWORD` | 서명 키 비밀번호 |

릴리스 버전명은 태그에서, Android 버전 코드는 워크플로 실행 번호에서 가져옵니다. 시크릿이 없거나 서명 검증이 실패하면 배포하지 않습니다. 장치의 업데이트 서명을 유지하려면 같은 배포 키를 계속 사용해야 합니다.

## Android 에뮬레이터 스모크

프로젝트 앱이 아직 설치되지 않은 전용 에뮬레이터로 실행합니다. 실제 기기나 기존 앱 데이터가 있는 환경에서는 실행하지 않습니다.

```sh
tests/device/run.sh emulator-5554
```

빌드한 앱과 테스트 APK를 별도로 지정할 수도 있습니다. 실행기는 APK의 패키지 ID와 설치된 instrumentation 연결을 검사합니다. 테스트는 Android 메인 화면을 열고 번들 Debian에서 `/etc/os-release`와 `apt-get --version`을 실제로 읽습니다. 실행 결과가 없거나 완료되지 않은 경우, 모두 건너뛴 경우는 실패입니다. 이전 DroidNative/Void용 테스트들은 이 판정에 포함하지 않습니다.

이 검사는 APT 네트워크 연결, 패키지 설치, Firefox 창, 하드웨어 가속을 검증하지 않습니다.
