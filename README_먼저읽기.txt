조아저씨 실시간 통역 앱 - GitHub 업로드용 프로젝트

포함 내용
- Android 앱 소스 코드
- Gradle 빌드 설정
- GitHub Actions 자동 APK 빌드 파일(.github/workflows/android.yml)

현재 앱 언어
- 한국어, 영어, 일본어, 중국어
- 태국어는 아직 포함되어 있지 않습니다.

GitHub에 올리는 방법
1. 이 ZIP 파일을 PC에서 압축 풀기
2. 안에 있는 JoAjeossiTranslator 폴더를 열기
3. 폴더 안의 파일과 폴더 전체를 GitHub 저장소 jojojojojo2243-del/JoAjeossiTranslator 에 업로드
4. 특히 .github 폴더가 빠지지 않도록 확인
5. 업로드 후 GitHub 저장소의 Actions에서 Android APK Build 실행 결과 확인

APK 받는 방법
1. GitHub 저장소에서 Actions 선택
2. 가장 최근 Android APK Build 실행 항목 선택
3. 성공(초록색 체크)이면 아래 Artifacts에서 JoAjeossiTranslator-debug-apk 다운로드
4. ZIP을 풀면 app-debug.apk가 있습니다.

참고
- 이 ZIP은 설치용 APK가 아니라 Android 프로젝트 전체입니다.
- APK 빌드는 GitHub Actions에서 진행됩니다.
- 첫 실행 시 마이크 권한을 허용해야 하며, 번역 모델은 처음 사용할 때 다운로드됩니다.
- 음성 인식은 휴대폰의 음성 인식 서비스 지원 여부와 네트워크 상태에 영향을 받습니다.
- 블루투스 이어폰 입력은 기기와 Android 버전에 따라 실제 테스트가 필요합니다.
