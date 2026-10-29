class Words {
  const Words(this.fa);

  final bool fa;

  String pick(String persian, String english) => fa ? persian : english;

  String get signIn => pick('ورود', 'Sign in');
  String get signInDescription => pick(
        'برای استفاده از Gozar، وارد حساب Firefox خود شوید.',
        'Use your Firefox account to continue with Gozar.',
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
}
