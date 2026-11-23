조아저씨 실시간 통역 1.1.0 - 태국어 포함

포함 기능(현재 프로젝트 기준)
- 한글 UI
- 영어/일본어/중국어/태국어/한국어 언어 선택
- Android 음성인식(SpeechRecognizer)
- Google ML Kit 온디바이스 번역 모델 다운로드 및 번역
- Android TTS 번역 음성 출력
- 블루투스 이어폰은 안드로이드 기본 오디오 경로를 사용(기기별 확인 필요)

중요: 이 ZIP은 설치용 APK가 아니라 APK 빌드용 프로젝트입니다. 이 프로젝트 자체의 APK 빌드와 갤럭시 S24 실기기 테스트는 아직 수행하지 않았습니다.

Android Studio 없이 GitHub에서 APK 만들기
1. GitHub에 로그인하고 새 저장소(repository)를 만듭니다. 예: joajeossi-translator
2. 이 ZIP의 압축을 풉니다. JoAjeossiTranslator 폴더 안의 파일과 폴더 전체를 저장소 루트에 업로드합니다. .github 폴더도 반드시 포함합니다.
3. GitHub 저장소의 Actions 탭을 엽니다.
4. 왼쪽에서 Android APK Build를 선택하고 Run workflow를 누릅니다.
5. 작업이 초록색 체크로 끝나면 해당 실행을 열고 아래 Artifacts에서 JoAjeossiTranslator-debug-apk를 다운로드합니다.
6. ZIP을 풀면 app-debug.apk가 있습니다. 이 APK를 갤럭시 S24로 옮겨 설치합니다.

참고 및 제한
- 음성인식은 휴대폰의 기본 음성 인식 서비스와 네트워크/언어팩 지원에 영향을 받습니다.
- 번역 모델은 첫 사용 시 언어별로 다운로드해야 합니다.
- 기본 MVP는 앱 화면이 켜져 있는 동안 한 번씩 듣고 번역하는 방식입니다. 진정한 동시 양방향 통역, 백그라운드 상시 통역, 전화 통화 오디오 캡처는 포함하지 않습니다.
- 블루투스 이어폰 마이크/출력 경로는 기종·이어폰·Android 버전에 따라 달라 실제 기기 확인이 필요합니다.
- GitHub Actions에서 생성되는 debug APK는 테스트용입니다. 정식 배포에는 별도 서명 키와 개인정보/마이크 고지 검토가 필요합니다.
