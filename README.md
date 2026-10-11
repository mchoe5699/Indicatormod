# QWERTY's Billboard (전광판) — Forge 1.20.1

PC에 있는 이미지로 월드에 2D/3D 전광판을 만드는 모드입니다.

## 빌드
- GitHub 저장소에 올리면 `.github/workflows/build.yml` 이 자동으로 빌드합니다 → Actions 탭의 **qwertys_billboard** 아티팩트에서 jar 다운로드.
- 로컬: `./gradlew build` → `build/libs/qwertys_billboard-1.0.0.jar` (`-slim.jar` 은 라이브러리가 빠진 것이니 쓰지 마세요)
- SVG(JSVG), WEBP·TIFF·TGA·PSD·ICO(TwelveMonkeys) 라이브러리는 jar 안에 같이 들어갑니다. 따로 설치할 것 없음.

## 사용법
1. 크리에이티브 탭 **QWERTY's 전광판** 의 `전광판` 블록을 설치 (조합법: 유리판 4 + 발광석 가루 4 + 레드스톤 1 → 4개)
2. 블록 우클릭 → 편집 창 (게임이 멈추지 않아 바로 미리 보임)
3. **이미지 추가** → PC 파일 선택 창 (여러 장 선택 가능). 창에 파일을 끌어다 놓아도 됩니다.

블록 자체는 보이지 않습니다. 비어 있을 때와 전광판 아이템을 들고 있을 때만 기준 위치가 선으로 표시됩니다.

## 기능
| 기능 | 내용 |
|---|---|
| 이미지 형식 | PNG, JPG, GIF(첫 프레임), BMP, WBMP, WEBP, TIFF, TGA, PSD, ICO/CUR, SVG/SVGZ |
| 투명 배경 | 투명한 부분은 자동 제외, 색 있는 부분만 잘라서 생성 |
| 단색 배경 제거 | 투명이 없는 JPG 등은 `배경:켬` + 허용 범위로 가장자리와 이어진 배경을 지움 |
| 2D / 3D | 2D = 얇은 판, 3D = 이미지 외곽선 모양 그대로 두께만큼 돌출 |
| 위치 | X/Y/Z, 1mm(0.001블록) ~ 블록 단위. 단위 버튼으로 블록/mm 표시 전환 |
| 회전 | Yaw/Pitch/Roll, +/- 단계 0.1°~90°, 입력칸에는 아무 값이나 (예: 12.37) |
| 크기·두께 | 가로 1mm ~ 128블록 (세로는 비율 유지), 두께 0.5mm ~ 32블록 |
| 개별 분리 | 한 이미지 안에서 떨어져 있는 모양을 각각의 오브젝트로 분리. 위치·회전 유지. 작은 점(잡티)은 가까운 모양에 붙음 |
| 발광 | 어두워도 항상 최대 밝기. `블록빛` 0~15 로 주변을 실제로 밝힘 |
| 색상 | 원본 / 원본×색 / 단색 / 무지개 / 무지개 물결 / 원본×무지개. 속도·퍼짐 조절 |
| 기타 | 이름, 표시/숨김, 불투명도, 복제, 순서 변경, 이미지만 교체 |
| [전체] | 목록 맨 위 `[전체]` 선택 시 전체 위치·회전·배율 조절, 외형 변경은 모든 오브젝트에 적용 |

입력칸 위에서 마우스 휠을 굴리면 현재 단계만큼 값이 바뀝니다.

## WorldEdit
- 이미지와 모든 설정이 블록 엔티티 NBT 에 저장되어 `//copy`, `//paste`, `//cut`, `//move`, `//stack`, 스키매틱 저장/불러오기 시 그대로 유지됩니다.
- 블록 상태 `facing` 이 전광판 전체의 기준 방향입니다. `//rotate 90` 등으로 돌리면 모든 오브젝트가 위치·회전째로 함께 돌아갑니다. (WorldEdit 은 블록을 90° 단위로만 돌립니다)
- `//flip` 은 방향만 뒤집고 이미지 자체를 좌우 반전하지는 않습니다.
- 구조물 블록 회전도 같은 방식으로 지원됩니다.

## 서버 설정 (`config/qwertys_billboard-common.toml`)
- `maxPartsPerBlock` (128) — 블록 하나의 최대 오브젝트 수
- `maxImageBytesPerBlock` (900000) — 블록 하나의 이미지 데이터 합계. 마인크래프트 청크 패킷 한도(약 2MB) 때문에 크게 올리지 마세요. 큰 전광판은 여러 블록에 나눠 쓰는 것이 안전합니다.
- `maxImageSize` (512) — 이미지 한 장 최대 픽셀
- `maxEditDistance` (64) — 편집 가능 거리

## 구조
```
block/   BillboardBlock (facing, light), BillboardBlockEntity (NBT 저장·동기화)
data/    DisplayPart (오브젝트 하나), ColorMode
network/ EditChunkPacket (32KB 조각 업로드), ServerEditHandler (재조립·권한·한도 검사), EditOps
client/  BillboardEditScreen (편집 창), BillboardRenderer (2D/3D 렌더), ClientImageCache (텍스처·3D 메시),
         ImageImporter / SvgRasterizer (불러오기·배경 제거·축소), ComponentSplitter (개별 분리), FileDialogs
```
