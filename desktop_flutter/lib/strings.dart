class Words {
  const Words(this.fa);

  final bool fa;

  String pick(String persian, String english) => fa ? persian : english;

  String get signIn => pick('ورود', 'Sign in');
  String get signInDescription => pick(
        'برای استفاده از Gozar، وارد حساب Firefox خود شوید.',
        'Use your Firefox account to continue with Gozar.',
      );
  String get createTitle => pick('ساخت حساب', 'Create account');
  String get createDescription => pick(
        'برای ساخت حساب Firefox اطلاعات زیر را وارد کنید.',
        'Enter your details to create a Firefox account.',
      );
  String get createAction => pick('ساخت حساب', 'Create account');
  String get haveAccount =>
      pick('حساب دارید؟ وارد شوید', 'Already have an account? Sign in');
  String get repeatPassword => pick('تکرار گذرواژه', 'Repeat password');
  String get passwordMismatch =>
      pick('دو گذرواژه یکسان نیستند.', 'The passwords do not match.');
  String get checkEmail => pick(
        'کد تأیید ارسال‌شده به ایمیل را وارد کنید.',
        'Enter the verification code sent to your email.',
      );
  String get verifyAndCreate =>
      pick('تأیید و ساخت حساب', 'Verify and create account');
  String get securityCheck =>
      pick('بررسی امنیتی Mozilla', 'Mozilla security check');
  String get securityInstruction => pick(
        'متن تصویر را وارد کنید.',
        'Enter the text shown in the image.',
      );
  String get securityAnswer => pick('متن تصویر', 'Image text');
  String get securityRequired =>
      pick('متن تصویر امنیتی را وارد کنید.', 'Enter the security image text.');
  String get securityFailed => pick(
        'بررسی امنیتی Mozilla کامل نشد؛ دوباره تلاش کنید.',
        'Mozilla’s security check could not be completed. Please try again.',
      );
  String get email => pick('ایمیل', 'Email');
  String get password => pick('گذرواژه', 'Password');
  String get verificationCode => pick('کد تأیید', 'Verification code');
  String get verifyAndSignIn => pick('تأیید و ورود', 'Verify and sign in');
  String get forgotPassword =>
      pick('گذرواژه را فراموش کرده‌اید؟', 'Forgot password?');
  String get createAccount => pick(
      'حساب ندارید؟ ساخت حساب Firefox', 'New here? Create a Firefox account');
  String get passwordNote => pick(
        'گذرواژه ذخیره نمی‌شود؛ ورود از طریق Mozilla انجام می‌شود.',
        'Your password is not stored. Sign-in takes place through Mozilla.',
      );
  String get settings => pick('تنظیمات', 'Settings');
  String get settingsSections => pick('بخش‌های تنظیمات', 'Settings sections');
  String get connectionSettings => pick('اتصال', 'Connection');
  String get displaySettings => pick('شخصی‌سازی', 'Personalization');
  String get generalSettings => pick('عمومی', 'General');
  String get accountSectionDetail =>
      pick('حساب‌ها و ورود', 'Accounts and sign-in');
  String get connectionSectionDetail =>
      pick('سرورها، پراکسی و مسیرها', 'Servers, proxy and routing');
  String get displaySectionDetail =>
      pick('زبان و ظاهر', 'Language and appearance');
  String get generalSectionDetail =>
      pick('به‌روزرسانی و پشتیبانی', 'Updates and support');
  String get back => pick('بازگشت', 'Back');
  String get minimize => pick('کوچک‌کردن', 'Minimize');
  String get maximize => pick('بزرگ‌کردن', 'Maximize');
  String get totalUsage => pick('مصرف این اتصال', 'Session traffic');
  String get pending => pick('در انتظار', 'Pending');
  String get account => pick('حساب Firefox', 'Firefox account');
  String get language => pick('زبان', 'Language');
  String get system => pick('سیستم', 'System');
  String get signOut => pick('خروج از حساب', 'Sign out');
  String get close => pick('بستن', 'Close');
  String get connected => pick('متصل است', 'Connected');
  String get disconnected => pick('قطع است', 'Disconnected');
  String get connecting => pick('در حال اتصال…', 'Connecting…');
  String get disconnecting => pick('در حال قطع…', 'Disconnecting…');
  String get tapConnect => pick('برای اتصال بزنید', 'Tap to connect');
  String get tapDisconnect => pick('برای قطع اتصال بزنید', 'Tap to disconnect');
  String get connect => pick('اتصال', 'Connect');
  String get disconnect => pick('قطع اتصال', 'Disconnect');
  String get server => pick('سرور', 'Server');
  String get bestServer => pick('بهترین سرور', 'Recommended server');
  String get chooseServer => pick('انتخاب سرور', 'Select server');
  String get loadingServers =>
      pick('در حال دریافت سرورها…', 'Loading servers…');
  String get searchServers =>
      pick('جست‌وجوی کشور یا شهر', 'Search countries or cities');
  String get noServers => pick('سروری پیدا نشد.', 'No servers found.');
  String get download => pick('دریافت', 'Download');
  String get upload => pick('ارسال', 'Upload');
  String get remaining => pick('حجم باقی‌مانده', 'Remaining');
  String get locations => pick('مکان‌ها', 'Locations');
  String get firewall => pick('دیوار آتش', 'Firewall');
  String get unavailable => pick('در دسترس نیست', 'Unavailable');
  String get exitIp => pick('آی‌پی خروجی', 'Exit IP');
  String get tunnelDevice => pick('رابط تونل', 'Tunnel device');
  String get usageUnavailable => pick('مصرف نامشخص', 'Usage unavailable');
  String get closeFailed => pick(
      'قطع امن تونل پیش از بستن برنامه انجام نشد؛ دوباره تلاش کنید.',
      'The tunnel could not be safely disconnected. Please try closing again.');
  String get tunnelNotice => pick(
        'نسخهٔ آزمایشی: ترافیک IPv6 از تونل عبور نمی‌کند و UDP به‌جز DNS پشتیبانی نمی‌شود.',
        'Experimental: IPv6 bypasses the tunnel, and UDP other than DNS is not supported.',
      );
  String get credentialsRequired =>
      pick('ایمیل و گذرواژه را وارد کنید.', 'Enter your email and password.');
  String get codeRequired =>
      pick('کد تأیید را وارد کنید.', 'Enter your verification code.');
  String get verifyLink => pick(
        'پیوند تأیید Mozilla را در ایمیل باز کنید، سپس دوباره ورود را بزنید.',
        'Open the Mozilla verification link in your email, then select Sign in again.',
      );
  String get serverError => pick(
      'دریافت فهرست سرورها ناموفق بود.', 'Could not load the server list.');
  String get browserError =>
      pick('باز کردن صفحه ناموفق بود.', 'Could not open the page.');
  String get coreMissing => pick('هستهٔ Gozar در کنار برنامه پیدا نشد.',
      'The Gozar core was not found next to the app.');
  String get coreStopped => pick(
      'ارتباط با هستهٔ برنامه قطع شد.', 'The connection to the core was lost.');
  String get timeout => pick('پاسخی دریافت نشد؛ اتصال اینترنت را بررسی کنید.',
      'No response. Check your internet connection.');
  String get signInRequired => pick('ابتدا وارد حساب Firefox شوید.',
      'Sign in to your Firefox account first.');
  String get connectionError => pick(
      'اتصال به سرور برقرار نشد. اینترنت را بررسی کنید.',
      'Could not reach the server. Check your internet connection.');
  String get adminRequired => pick(
      'برای اتصال، برنامه به دسترسی مدیر سیستم نیاز دارد.',
      'The tunnel needs administrator privileges.');
  String get windowsAdminRequired => pick(
        'برای اتصال، برنامه را با دسترسی Administrator اجرا کنید.',
        'Run the app as Administrator to connect.',
      );
  String get appearance => pick('ظاهر', 'Appearance');
  String get lightTheme => pick('روشن', 'Light');
  String get darkTheme => pick('تیره', 'Dark');
  String get rememberServer =>
      pick('به‌خاطر سپردن سرور آخر', 'Remember last server');
  String get proxyChain => pick('زنجیرهٔ پراکسی', 'Proxy chain');
  String get proxyChainHint => pick(
      'هر خط یک پراکسی؛ برای نمونه socks5://127.0.0.1:1080',
      'One proxy per line, for example socks5://127.0.0.1:1080');
  String get proxyChainDetail => pick(
      'پراکسی‌های SOCKS5 یا HTTP به ترتیب استفاده می‌شوند.',
      'SOCKS5 or HTTP proxies are used in order.');
  String get noProxyChain => pick('بدون پراکسی میانی', 'No upstream proxies');
  String get save => pick('ذخیره', 'Save');
  String get cancel => pick('انصراف', 'Cancel');
  String get manageAccounts => pick('مدیریت حساب‌ها', 'Manage accounts');
  String get savedAccounts =>
      pick('حساب‌های ذخیره‌شده در گذر', 'Saved Gozar accounts');
  String get addAccount => pick('افزودن حساب', 'Add account');
  String get activeAccount => pick('فعال', 'Active');
  String get switchDisconnect => pick(
      'برای تغییر یا افزودن حساب، اتصال قطع می‌شود.',
      'The VPN will disconnect before you switch or add an account.');
  String get systemAccounts => pick(
      'انتخاب ایمیل از حساب‌های سیستم', 'Choose an email from system accounts');
  String get noSystemAccounts => pick(
      'حساب ایمیلی در حساب‌های سیستم در دسترس نیست؛ می‌توانید ایمیل را دستی وارد کنید.',
      'No system email accounts are available. You can enter your email manually.');
  String get systemPickerUnavailable => pick(
      'انتخاب حساب سیستم روی این دستگاه در دسترس نیست؛ ایمیل را دستی وارد کنید.',
      'The system account picker is unavailable on this device. Enter your email manually.');
  String get desktopRoutingLimits => pick(
      'مسیریابی بر اساس برنامه و دامنه در نسخهٔ دسکتاپ هنوز پشتیبانی نمی‌شود.',
      'Per-app and per-domain routing are not yet supported on desktop.');
  String get routing => pick('مسیریابی', 'Routing');
  String get updates => pick('بررسی به‌روزرسانی', 'Check for updates');
  String get about => pick('درباره و مجوز', 'About and license');
  String get updateAvailable =>
      pick('نسخهٔ تازه در دسترس است:', 'A new release is available:');
  String get updateCurrent =>
      pick('نسخهٔ شما به‌روز است.', 'You are up to date.');
  String get updateFailed =>
      pick('بررسی به‌روزرسانی انجام نشد.', 'Could not check for updates.');
  String get openRelease => pick('دیدن نسخه', 'View release');
  String get donate => pick('حمایت از گذر', 'Support Gozar');
  String get donateMessage => pick(
      'اگر گذر برایتان مفید بوده، می‌توانید با رمزارز TON از توسعهٔ آن حمایت کنید.',
      'If Gozar is useful to you, you can support its development with TON.');
  String get copyAddress => pick('کپی نشانی', 'Copy address');
  String get addressCopied => pick('نشانی کپی شد.', 'Address copied.');
  String get sourceCode => pick('کد منبع', 'Source code');

  String get consoleSettings => pick('کنسول و گیمینگ', 'Console and gaming');
  String get consoleSectionDetail => pick(
      'درگاه اتصال کنسول، UDP و پروفایل Xbox / PlayStation',
      'Console gateway, datagram relay and Xbox / PlayStation profile');
  String get consoleProfile => pick('پروفایل کنسول', 'Console profile');
  String get consoleProfileDetail => pick(
      'تنظیمات پیشنهادی مسیر و DNS با توجه به نوع کنسول.',
      'Routing and DNS defaults tuned for the selected console.');
  String get consoleGateway => pick('درگاه کنسول', 'Console gateway');
  String get consoleGatewayDetail => pick(
      'کنسول را با همین رایانه به اینترنت وصل می‌کند.',
      'Connects the console to the internet through this computer.');
  String get consoleGatewayOn => pick('درگاه فعال است', 'Gateway is running');
  String get consoleGatewayOff => pick('درگاه خاموش است', 'Gateway is off');
  String get consoleGatewayNeedsTunnel => pick(
      'برای روشن‌کردن درگاه ابتدا وصل شوید.',
      'Connect first before starting the gateway.');
  String get gatewayEndpoint => pick('نشانی درگاه', 'Gateway endpoint');
  String get gatewayLogin => pick('نام کاربری', 'Username');
  String get gatewayPassword => pick('گذرواژه', 'Password');
  String get gatewayRotate => pick('ساخت گذرواژهٔ تازه', 'Rotate password');
  String get gatewayRotated => pick(
      'گذرواژهٔ تازه ساخته شد؛ گذرواژهٔ قبلی دیگر کار نمی‌کند.',
      'A new password was created. The old one no longer works.');
  String get gatewayClients =>
      pick('کنسول‌های متصل', 'Connected consoles');
  String get gatewayTraffic =>
      pick('ترافیک درگاه', 'Gateway traffic');
  String get gatewayAllow => pick('شبکهٔ مجاز', 'Allowed network');
  String get gatewayAllowHint => pick(
      'فقط نشانی‌های همین فهرست می‌توانند از درگاه استفاده کنند.',
      'Only addresses in this list may use the gateway.');
  String get udpRelay => pick('انتقال UDP', 'Datagram relay');
  String get udpRelayDetail => pick(
      'بازی‌ها، تماس صوتی، QUIC و DNS را روی همان تونل می‌برد.',
      'Carries games, voice calls, QUIC and DNS over the same tunnel.');
  String get udpPorts => pick('درگاه‌های اضافی UDP', 'Extra datagram ports');
  String get udpPortsHint => pick(
      'فهرست درگاه‌ها با کاما؛ برای نمونه 3074,3479,9295',
      'Comma-separated ports, for example 3074,3479,9295');
  String get consoleSteps => pick('راهنمای کنسول', 'Console guide');
  String get consolePlaystation => 'PlayStation 5';
  String get consoleXbox => 'Xbox Series X|S';
  String get playstationSteps => pick(
      '1) Settings → Network → Settings → Set Up Internet Connection.\n'
          '2) انتخاب Wi-Fi یا LAN → Advanced Settings.\n'
          '3) Proxy Server را روی Use بگذارید و نشانی درگاه و درگاه را وارد کنید.\n'
          '4) DNS Settings را روی Manual بگذارید و DNS را برابر نشانی درگاه قرار دهید.\n'
          '5) Test Internet Connection را بزنید.',
      '1) Settings → Network → Settings → Set Up Internet Connection.\n'
          '2) Pick Wi-Fi or LAN → Advanced Settings.\n'
          '3) Set Proxy Server to Use and enter the gateway address and port.\n'
          '4) Set DNS Settings to Manual and use the gateway address as DNS.\n'
          '5) Run Test Internet Connection.');
  String get xboxSteps => pick(
      '1) Settings → General → Network settings → Advanced settings.\n'
          '2) DNS settings → Manual و نشانی درگاه را وارد کنید.\n'
          '3) Xbox پراکسی ندارد؛ IP و DNS را دستی روی همین رایانه تنظیم کنید\n'
          '   و رایانه را به‌عنوان درگاه/شبکهٔ مشترک با کنسول شریک کنید.\n'
          '4) Test network connection را بزنید.',
      '1) Settings → General → Network settings → Advanced settings.\n'
          '2) DNS settings → Manual and enter the gateway address.\n'
          '3) Xbox has no proxy field; point the console DNS at this computer\n'
          '   and share the connection so the computer acts as its gateway.\n'
          '4) Run Test network connection.');
  String get consoleUdpNotice => pick(
      'کنسول‌ها فقط TCP را از پراکسی می‌فرستند؛ UDP بازی از درگاه رایانه عبور می‌کند.',
      'Consoles send only TCP through a proxy; game datagrams flow through the computer gateway.');
  String get gatewayStatistics => pick('آمار', 'Statistics');
  String get copyAll => pick('کپی همه', 'Copy all');
  String get connectionDetails => pick('جزئیات اتصال', 'Connection details');
}
