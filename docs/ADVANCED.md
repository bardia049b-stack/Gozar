# مسیرهای پیشرفتهٔ اتصال (Advanced)

از نسخهٔ ۱.۱۰.۳ شش قابلیت پیشرفته در هستهٔ دسکتاپ پیاده‌سازی شده است. همهٔ آن‌ها
**به‌صورت پیش‌فرض خاموش** هستند و در تنظیمات با برچسب «پایدار» (نه «آزمایشی») دیده
می‌شوند، چون هر کدام پیاده‌سازی واقعی و تست خودکار دارند. هر قابلیت یک مسیر
**جانشین خودکار** دارد؛ اگر کار نکند، اتصال قطع نمی‌شود و به مسیر استاندارد
برمی‌گردد.

مسیر فعالِ آخرین اتصال در همان صفحه نشان داده می‌شود (`live_path`)، پس کاربر
می‌تواند ببیند کدام روش واقعاً استفاده شده است.

| قابلیت | چه کار می‌کند | جانشین خودکار | فایل |
|---|---|---|---|
| DoQ | DNS روی QUIC (RFC 9250، ALPN `doq`) | DoH و سپس DNS معمولی | `internal/advanced/doq.go` |
| ECH | رمزنگاری ClientHello با کلید عمومی مقصد (RFC 9460) | TLS معمولی | `internal/advanced/echconfig.go` |
| Domain fronting | SNI یک دامنهٔ پوششی، ولی CONNECT/Host مقصد واقعی | TLS معمولی | `internal/advanced/advanced.go` |
| TFO | TCP Fast Open روی لینوکس (`TCP_FASTOPEN_CONNECT=30`) | TCP معمولی | `internal/advanced/tfo_linux.go` |
| MPTCP | Multipath TCP با `Dialer.SetMultipathTCP` | TCP معمولی | `internal/advanced/advanced.go` |
| WSS | تونل داخل WebSocket روی TLS (RFC 6455، زیرپروتکل `gozar-tunnel`) | HTTP/2 سپس HTTP/1.1 | `internal/advanced/wss.go` |

## نکته‌های مهم و صادقانه

- **ECH** از `crypto/tls` استاندارد Go استفاده می‌کند، نه uTLS. یعنی ECH واقعی
  است ولی اثرانگشت TLS آن مثل مرورگر کروم نیست. اگر شبکه‌ای روی اثرانگشت JA3
  فیلتر کند، ECH به‌تنهایی کافی نیست.
- **Domain fronting** فقط SNI را عوض می‌کند؛ `CONNECT` و هدر `Host` همچنان مقصد
  واقعی هستند. این روش فقط وقتی کار می‌کند که پروکسی/CDN مقصد واقعاً fronting را
  بپذیرد. تست CI درستیِ SNI پوششی و بازگشت خودکار را ثابت می‌کند، نه اینکه یک
  CDN مشخص در اینترنت امروز آن را می‌پذیرد.
- **TFO** فقط روی لینوکس اثر دارد و به `net.ipv4.tcp_fastopen` بستگی دارد؛ روی
  ویندوز/مک‌اواس به‌طور خودکار TCP معمولی می‌شود.
- **MPTCP** به پشتیبانی هستهٔ سیستم‌عامل نیاز دارد
  (`/proc/sys/net/mptcp/enabled`). اگر خاموش باشد، Go بی‌صدا به TCP برمی‌گردد.
- **WSS** یک پروتکل سمت‌سرور لازم دارد (هدر `X-Gozar-Target` و
  `Proxy-Authorization: Bearer`). سرورهای عمومی WebSocket این هدرها را نمی‌فهمند؛
  این گزینه برای زمانی است که کاربر یک رلهٔ سازگار در اختیار دارد.
- تغییر گزینه‌های ترابرد (ECH/fronting/TFO/MPTCP/WSS) در **اتصال بعدی** اعمال
  می‌شود؛ DoQ بلافاصله اعمال می‌شود.
- **اندروید** این شش مسیر را ندارد. هستهٔ اندروید (Kotlin) جداست و این قابلیت‌ها
  فعلاً فقط دسکتاپ هستند.

## تنظیم پارامترها

در تنظیمات → «اتصال پیشرفته» → «پارامترهای مسیر»:

| فیلد | پیش‌فرض | توضیح |
|---|---|---|
| `doq_server` | `dns.adguard-dns.com:853` | سرور DoQ |
| `front_host` | خالی | دامنهٔ پوششی برای SNI |
| `wss_path` | `/gozar-tunnel` | مسیر WebSocket روی رله |
| `ech_config_list` | خالی | `ECHConfigList` به‌صورت base64 (خروجی HTTPS/SVCB) |

از طریق RPC هم قابل تنظیم است:

```
advanced                                  # وضعیت همهٔ گزینه‌ها + live_path
advanced_set name=wss enabled=true
advanced_params doq_server=dns.adguard-dns.com:853 front_host=cover.example
```

## تست‌هایی که این مسیرها را ثابت می‌کنند

همه روی GitHub Actions اجرا می‌شوند (`Test All Platforms` و `Hardening`):

- `internal/advanced`: handshake واقعی ECH با سرور TLS خودِ Go و بررسی اینکه
  ECH رد شده → بازگشت به TLS؛ fronting با بررسی SNI پوششی؛ TFO/MPTCP با
  `/proc/sys/net/mptcp/enabled`؛ WSS با انتقال ۲۵۶ کیلوبایت و رمز اشتباه؛
  DoQ با پاسخ، ID صفر، استفادهٔ مجدد از اتصال و شکست.
- `internal/tunnel`: یک پروکسی واقعی که روی یک پورت هم HTTP/2 CONNECT و هم
  WebSocket upgrade می‌دهد؛ ثابت می‌کند WSS واقعاً استفاده می‌شود و اگر شکست
  بخورد به HTTP/2 برمی‌گردد.
- `internal/dnsproxy`: DoQ ترجیح دارد (شمارندهٔ DoH صفر می‌ماند)، با مرگ سرور
  DoQ، سرویس DoH جای آن را می‌گیرد.
- `desktop_flutter/test/ui_test.dart`: هر شش کلید پیش‌فرض خاموش‌اند، روشن/خاموش
  کردن کار می‌کند و دیالوگ پارامترها ذخیره می‌شود.

سرورهای آزمایشی (`internal/advancedtest`) واقعی هستند: QUIC واقعی برای DoQ و
TLS+WebSocket واقعی برای WSS. آن‌ها هرگز به اینترنت عمومی وصل نمی‌شوند؛ مقصد
درخواستی فقط ثبت می‌شود تا تست ثابت کند کلاینت هدر درست را فرستاده است.
