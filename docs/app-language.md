# 앱 언어

Galaxunil은 기본적으로 기기의 언어 설정을 따릅니다. 기기 언어가 한국어이면 앱 UI도 한국어로 표시됩니다. 지원되지 않는 언어에서는 영어를 사용합니다.

Android 13 이상에서는 앱의 **설정 → 앱 언어**에서 **기기 언어 사용**, **한국어**, **English**를 선택할 수 있습니다. Android 시스템 설정의 앱 언어에서도 같은 설정을 바꿀 수 있습니다. Android 10~12에서는 기기 전체의 언어를 따릅니다.

이 설정은 Galaxunil UI에 적용됩니다. Debian 패키지의 이름과 설명, Linux 앱 내부 언어, 키보드 배열, 터미널과 진단 로그는 별도로 동작합니다.

## 리소스 검증

```sh
python3 -m unittest discover -s tests -p test_localization.py
```

영어와 한국어의 문자열 키 일치 여부, 서식 인수, Android 앱 언어 선언을 검증합니다.
