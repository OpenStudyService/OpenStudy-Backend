# 프로필 사진 S3 버킷·권한 요청 (#120)

프로필 사진은 S3 비공개 버킷에 둡니다(ADR-M14-001 M14-D03). 사진은 앱이 직접 읽어 내려주고(M14-D13), 버킷을 공개하거나 서명 URL을 쓰지 않습니다. 아래 내용을 인프라 담당에게 요청합니다. 계약은 [account-collab-api.md](../design/foundation/account-collab-api.md) "프로필 사진"에 있습니다.

## 요청 내용

| 항목 | 요청 |
| --- | --- |
| 버킷 | 서비스 전용 버킷 하나. 이름 예: `<name_prefix>-avatars`. 리전은 서비스와 같게(`ap-northeast-2`) |
| 공개 차단 | Block Public Access 네 항목을 모두 켭니다. 버킷 정책으로 공개 읽기를 주지 않습니다 |
| 객체 소유권 | Bucket owner enforced(ACL 비활성) |
| 암호화 | 기본 암호화 SSE-S3. 앱도 객체마다 `AES256`을 요청합니다 |
| 전송 | 버킷 정책으로 `aws:SecureTransport=false` 요청을 거부합니다 |
| 버전 관리 | 끕니다. 사진을 바꾸면 이전 파일을 지우는 동작이 계약이라, 버전이 남으면 지운 사진이 보관됩니다 |
| 수명 주기 | 필요 없습니다(앱이 지웁니다). 다만 미완료 멀티파트 업로드 정리 규칙(7일)은 넣어도 됩니다 |
| CORS | 필요 없습니다. 브라우저는 S3에 직접 접근하지 않습니다 |

## 앱 실행 역할의 IAM 권한

앱 컨테이너(인스턴스 또는 작업 역할)에 다음 권한만 줍니다. 자격증명은 AWS 기본 체인으로 얻으므로 액세스 키를 환경 변수에 넣지 않습니다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::<bucket>/avatars/*"
    }
  ]
}
```

- `s3:ListBucket`은 주지 않습니다. 앱은 키를 직접 알고 있어 목록이 필요 없습니다.
  - 이 권한이 없으면 없는 객체를 읽을 때 S3가 404 대신 403을 줍니다.
  - 앱은 DB에 키가 있는 사진만 읽으므로, 이 경우는 파일이 지워진 예외 상황뿐입니다.

## 앱 설정

| 환경 변수 | 값 |
| --- | --- |
| `OPENSTUDY_AVATAR_STORAGE` | `s3` |
| `OPENSTUDY_AVATAR_S3_BUCKET` | 위 버킷 이름 |
| `OPENSTUDY_AVATAR_S3_REGION` | `ap-northeast-2` |

`OPENSTUDY_AVATAR_STORAGE`를 지정하지 않으면 컨테이너 안의 `./data/avatars`에 저장합니다. 이 경로는 재시작하면 사라지므로 운영에서는 반드시 `s3`로 둡니다.

## 확인 결과

- 요청 일자·담당·처리 결과는 아직 없습니다. 요청하고 운영에서 확인한 뒤 이 절에 남깁니다.
- 운영 확인 항목
  - 업로드 후 S3 객체 생성
  - 사진 교체 후 이전 객체 삭제
  - 버킷 공개 차단
  - 다른 지점 직원 조회 시 404
