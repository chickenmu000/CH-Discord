# CH-Discord

Paper 1.21.x 용 **디스코드 콘솔 채널 플러그인**입니다. DiscordSRV 의 콘솔 채널 기능만 가볍게 만든 것으로, 다른 기능(채팅 연동, 계정 연동, 프레전스, 슬래시 명령어 등)은 없습니다.

1. **콘솔 내역** : 서버 콘솔에 찍히는 로그를 디스코드 채널로 보냅니다.
2. **명령어 실행** : 그 채널에 올린 메시지를 서버 콘솔 명령어로 실행합니다. (`/` 없이 `say hello`, `list` 처럼 입력)

## 요구 사항
- Paper 1.21.x / Java 21
- 디스코드 봇 (Bot 토큰, **MESSAGE CONTENT INTENT** 켜기, 콘솔 채널의 메시지 보기/보내기 권한)
- JDA 5 는 서버가 처음 시작할 때 자동으로 내려받습니다. (`plugin.yml` 의 `libraries`)

## 설정
1. 플러그인을 넣고 서버를 한 번 켠 뒤 `plugins/CH-Discord/config.yml` 에서 `bot-token`, `console-channel-id` 를 채웁니다.
2. `/chdiscord reload` (또는 서버 재시작)

## 명령어
| 명령어 | 설명 | 권한 |
| --- | --- | --- |
| `/chdiscord reload` (`/chd reload`) | config.yml 다시 불러오고 디스코드에 재연결 | `chdiscord.admin` |

## config.yml
| 항목 | 기본값 | 설명 |
| --- | --- | --- |
| `bot-token` | - | 봇 토큰 |
| `console-channel-id` | - | 콘솔 채널 ID |
| `console.levels` | `INFO, WARN, ERROR, FATAL` | 보낼 로그 레벨 (비우면 전송 안 함) |
| `console.use-code-blocks` | `true` | 코드 블록으로 감싸서 전송 |
| `console.prefix` | `[{date} {level}] ` | 줄 앞 형식 (`{date}` `{level}` `{name}`) |
| `console.send-interval-seconds` | `2` | 로그를 모아서 보내는 간격 |
| `console.max-stacktrace-lines` | `20` | 예외 스택트레이스 최대 줄 수 (0 = 안 보냄) |
| `console.ignore-patterns` | `[]` | 이 정규식에 맞는 줄은 전송 안 함 |
| `command.block-bots` | `true` | 봇이 보낸 메시지는 실행 안 함 |
| `command.filter-mode` / `filter-commands` | `blacklist` / `[]` | 블랙리스트(목록 명령어 차단) 또는 화이트리스트(목록 명령어만 허용) |
| `command.usage-log` | `console-usage.log` | 디스코드에서 실행한 명령어 기록 (누가·언제·무엇을) |
| `command.cancel-if-log-fails` | `true` | 기록 실패 시 명령어 실행 안 함 |

## 동작 / 주의
- 로그는 모아서 2000자 이내로 나누어 보냅니다. 서버 시작 로그도 디스코드 연결이 끝나면 함께 전달됩니다. (큐 최대 5000줄)
- 색 코드(ANSI, `§`)는 제거하고 보냅니다. JDA 자체 로그는 무한 반복 방지를 위해 보내지 않습니다.
- **콘솔 채널에 글을 쓸 수 있는 사람은 누구나 서버 콘솔 명령어를 실행할 수 있습니다.** 채널 권한을 운영진에게만 주고, 필요하면 `filter-commands` 로 `op`, `stop` 등을 막으세요.
- 이미 다른 플러그인(DiSky 등)이 같은 봇 토큰으로 접속 중이어도 동작하지만, 별도 봇을 쓰는 것을 권장합니다.

## 빌드
```
mvn package
```
