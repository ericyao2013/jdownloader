//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.swing.JComponent;
import javax.swing.JLabel;

import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.swing.components.ExtTextField;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.Property;
import jd.gui.swing.components.linkbutton.JLink;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "openload.co" }, urls = { "https?://(?:www\\.)?(?:openload\\.(?:io|co)|oload\\.co)/(?:f|embed)/[A-Za-z0-9_\\-]+|https?://(?:www\\.)?openload\\.co/stream/[A-Za-z0-9_\\-]+~.+" })
public class OpenLoadIo extends antiDDoSForHost {
    public OpenLoadIo(PluginWrapper wrapper) {
        super(wrapper);
        /* Server doesn't like it when we open too many connections in a short time */
        this.setStartIntervall(2000);
        this.enablePremium("https://openload.co/register");
    }

    @Override
    public String rewriteHost(final String host) {
        if ("openload.io".equals(host) || "openload.co".equals(host) || "oload.co".equals(host)) {
            return "openload.co";
        }
        return super.rewriteHost(host);
    }

    @Override
    public String getAGBLink() {
        return "https://openload.co/tos";
    }

    /* Constants */
    /* Status 2015-09-08: free API working again, site-handling remains broken! */
    private static final boolean          enable_api_free              = true;
    private static final boolean          enable_api_login             = true;
    private static final String           api_base                     = "https://api.openload.co/1";
    private static final long             api_responsecode_private     = 403;
    /* Connection stuff */
    private static final boolean          FREE_RESUME                  = true;
    private static final int              FREE_MAXCHUNKS               = 0;
    private static final int              FREE_MAXDOWNLOADS            = 20;
    private int                           api_responsecode             = 0;
    private String                        api_msg                      = null;
    private LinkedHashMap<String, Object> api_data                     = null;
    /* Website related things */
    private static final long             trust_cookie_age             = 300000l;
    private static final boolean          ACCOUNT_FREE_RESUME          = true;
    private static final int              ACCOUNT_FREE_MAXCHUNKS       = 0;
    private static final int              ACCOUNT_FREE_MAXDOWNLOADS    = 20;
    private static final boolean          ACCOUNT_PREMIUM_RESUME       = true;
    private static final int              ACCOUNT_PREMIUM_MAXCHUNKS    = 0;
    private static final int              ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    private static final String           type_directurl               = "https?://(?:www\\.)?openload\\.co/stream/[A-Za-z0-9_\\-]+~.+";
    /* don't touch the following! */
    private static AtomicInteger          maxPrem                      = new AtomicInteger(1);

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(final DownloadLink link) {
        /* Force https & correct embedded urls */
        if (!link.getDownloadURL().matches(type_directurl)) {
            link.setUrlDownload("https://openload.co/f" + link.getDownloadURL().substring(link.getDownloadURL().lastIndexOf("/")));
        }
    }

    @Override
    public AccountBuilderInterface getAccountFactory(InputChangedCallbackInterface callback) {
        return new OpenLoadIoAccountFactory(callback);
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public boolean checkLinks(final DownloadLink[] urls) {
        if (urls == null || urls.length == 0) {
            return false;
        }
        try {
            final Browser br = new Browser();
            br.setCookiesExclusive(true);
            final StringBuilder sb = new StringBuilder();
            final ArrayList<DownloadLink> links = new ArrayList<DownloadLink>();
            LinkedHashMap<String, Object> api_data = null;
            LinkedHashMap<String, Object> api_data_singlelink = null;
            int index = 0;
            while (true) {
                links.clear();
                while (true) {
                    /* we test 50 links at once (50 tested, more might be possible) */
                    if (index == urls.length || links.size() > 50) {
                        break;
                    }
                    links.add(urls[index]);
                    index++;
                }
                sb.delete(0, sb.capacity());
                for (final DownloadLink dl : links) {
                    final String fid = getFID(dl);
                    dl.setLinkID(fid);
                    sb.append(fid);
                    sb.append("%2C");
                }
                br.getPage(api_base + "/file/info?file=" + sb.toString());
                api_data = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
                api_data = (LinkedHashMap<String, Object>) api_data.get("result");
                for (final DownloadLink dl : links) {
                    final String fid = getFID(dl);
                    api_data_singlelink = (LinkedHashMap<String, Object>) api_data.get(fid);
                    final long status = JavaScriptEngineFactory.toLong(api_data_singlelink.get("status"), 404);
                    if (status == api_responsecode_private) {
                        /* Private file */
                        dl.setName(fid);
                        dl.setAvailable(true);
                        continue;
                    } else if (api_data_singlelink == null || status != 200) {
                        dl.setName(fid);
                        dl.setAvailable(false);
                        continue;
                    }
                    final String filename = (String) api_data_singlelink.get("name");
                    final long filesize = JavaScriptEngineFactory.toLong(api_data_singlelink.get("size"), 0);
                    final String sha1 = (String) api_data_singlelink.get("sha1");
                    /* Trust API */
                    dl.setAvailable(true);
                    // Check filename from any decrypter first, please do not remove
                    if (dl.getFinalFileName() == null) {
                        dl.setFinalFileName(filename);
                    }
                    dl.setDownloadSize(filesize);
                    dl.setSha1Hash(sha1);
                }
                if (index == urls.length) {
                    break;
                }
            }
        } catch (final Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        final boolean checked = checkLinks(new DownloadLink[] { downloadLink });
        // we can't throw exception in checklinks! This is needed to prevent multiple captcha events!
        if (!checked && hasAntiddosCaptchaRequirement()) {
            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
        } else if (!checked || !downloadLink.isAvailabilityStatusChecked()) {
            downloadLink.setAvailableStatus(AvailableStatus.UNCHECKABLE);
        } else if (!downloadLink.isAvailable()) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        prepBRAPI(this.br);
        if (api_responsecode == api_responsecode_private) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Private files can only be downloaded by their owner/uploader");
        }
        doFree(downloadLink, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink", null);
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, Account acc) {
        if (acc != null && AccountType.PREMIUM.equals(acc.getType())) {
            return false;
        }
        if (acc == null && enable_api_free) {
            return true;
        }
        if (acc != null && isAPIAccount(acc)) {
            return true;
        }
        return false;
    }

    @SuppressWarnings({ "deprecation", "unchecked" })
    private void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty, final Account account) throws Exception, PluginException {
        final boolean is_directurl = downloadLink.getDownloadURL().matches(type_directurl);
        final String fid = getFID(downloadLink);
        String dllink;
        if (is_directurl) {
            dllink = downloadLink.getDownloadURL();
        } else {
            dllink = checkDirectLink(downloadLink, directlinkproperty);
        }
        if (dllink == null) {
            String ticket;
            String waittime;
            int waittime_int;
            boolean captcha = false;
            String captcha_response = "null";
            try {
                if ((account == null && enable_api_free) || (account != null && isAPIAccount(account))) {
                    getPageAPI(api_base + "/file/dlticket?file=" + fid + "&" + getAPILoginString(account));
                    final long timestampBeforeCaptcha = System.currentTimeMillis();
                    api_data = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
                    api_data = (LinkedHashMap<String, Object>) api_data.get("result");
                    ticket = (String) api_data.get("ticket");
                    waittime_int = ((Number) api_data.get("wait_time")).intValue();
                    waittime = Integer.toString(waittime_int);
                    final Object captchao = api_data.get("captcha_url");
                    if (captchao instanceof String) {
                        captcha = true;
                        final String captchaurl = (String) captchao;
                        captcha_response = this.getCaptchaCode(captchaurl, downloadLink);
                    }
                    if (ticket == null) {
                        logger.warning("Ticket is null");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    this.waitTime(timestampBeforeCaptcha, waittime_int, downloadLink);
                    this.br.getPage(api_base + "/file/dl?file=" + fid + "&ticket=" + Encoding.urlEncode(ticket) + "&captcha_response=" + captcha_response + "&" + getAPILoginString(account));
                    updatestatuscode();
                    if (captcha && this.api_responsecode == 403 && "Captcha not solved correctly".equals(this.api_msg)) {
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    api_data = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
                    api_data = (LinkedHashMap<String, Object>) api_data.get("result");
                    dllink = (String) api_data.get("url");
                    if (dllink == null) {
                        handleAPIErrors();
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
            } catch (PluginException e1) {
                if (e1.getMessage() != null && (e1.getMessage().contains("The owner of this file doesn't allow API downloads") || e1.getMessage().contains("out of capacity for non-browser"))) {
                } else {
                    throw e1;
                }
            }
            if (StringUtils.isEmpty(dllink)) {
                br.addAllowedResponseCodes(500);
                br.setFollowRedirects(true);
                getPage(downloadLink.getDownloadURL());
                if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 500) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                if (br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                String html = br.toString();
                // not sure if this is actually used
                String result = decode(html);
                if (result == null) {
                    result = decode20161010(html);
                }
                dllink = br.getURL("/stream/" + result + "?mime=true").toString();
                boolean fol = br.isFollowingRedirects();
                try {
                    br.openGetConnection(dllink).disconnect();
                    dllink = br.getURL();
                } finally {
                    br.setFollowRedirects(fol);
                }
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (is_directurl) {
                logger.info("directurl seems to have expired");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            br.followConnection();
            this.updatestatuscode();
            this.handleAPIErrors();
            /* We usually use their API so no matter what goes wrong here - a retry should help! */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 3 * 60 * 1000l);
        }
        if (!is_directurl) {
            /*
             * It makes no sense to save the directurl property on a DownloadLink which already is a directlink, especially because we want
             * to display the user a "FILE_NOT_FOUND" message once such directurls expire.
             */
            downloadLink.setProperty(directlinkproperty, dllink);
        }
        dl.startDownload();
    }

    protected String decode20161010(String html) {
        String result;
        // 10.10.16
        // x
        String[] inputs = new Regex(html, "<span id=\"([^\"]+)\">([^<]+)</span>\\s*<span id=\"([^\"]+)\">([^<]+)</span>\\s*<span id=\"streamurl\">").getRow(0);
        String y = Encoding.htmlDecode(inputs[1]);
        String x = Encoding.htmlDecode(inputs[3]);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < y.length(); i++) {
            char j = y.charAt(i);
            if ((j >= 33) && (j <= 126)) {
                s.insert(i, Character.toString((char) (33 + ((j + 14) % 94))));
            } else {
                s.insert(i, Character.toString(j));
            }
        }
        result = s.toString().substring(0, s.toString().length() - 1) + Character.toString((char) (s.toString().charAt(s.toString().length() - 1) + 2));
        return result;
    }

    protected String decode(String html) throws Exception {
        String hiddenUrl = new Regex(html, "<span id=\"hiddenurl\">(.*?)</span>").getMatch(0);
        String decoded = new org.jdownloader.encoding.AADecoder(html).decode();
        decoded = new Regex(decoded, "(var s=\\[\\]\\;.*?)\\$\\(\"\\#streamurl\"\\)").getMatch(0);
        final ScriptEngineManager manager = JavaScriptEngineFactory.getScriptEngineManager(null);
        final ScriptEngine engine = manager.getEngineByName("javascript");
        String result = null;
        try {
            engine.put("x", Encoding.htmlOnlyDecode(hiddenUrl));
            engine.eval(decoded);
            result = engine.get("str").toString();
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                con = br2.openHeadConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    @SuppressWarnings("deprecation")
    private String getFID(final DownloadLink dl) {
        final String fid;
        if (dl.getDownloadURL().matches(type_directurl)) {
            fid = new Regex(dl.getDownloadURL(), "/stream/([A-Za-z0-9_\\-]+)").getMatch(0);
        } else {
            fid = dl.getDownloadURL().substring(dl.getDownloadURL().lastIndexOf("/") + 1);
        }
        return fid;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private static Object LOCK = new Object();

    private void loginAPI(final Account account) throws Exception {
        synchronized (LOCK) {
            prepBRAPI(this.br);
            getPageAPI(api_base + "/account/info?" + getAPILoginString(account));
        }
    }

    private void loginWebsite(final Account account) throws Exception {
        synchronized (LOCK) {
            final boolean followsRedirect = br.isFollowingRedirects();
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    if (System.currentTimeMillis() - account.getCookiesTimeStamp("") <= trust_cookie_age) {
                        /* We trust these cookies --> Do not check them */
                        return;
                    }
                    if (this.br.containsHTML("href=\"/logout\"")) {
                        /* Cookies are valid - refresh timestamp */
                        account.saveCookies(this.br.getCookies(this.getHost()), "");
                        return;
                    }
                    /* Cookies failed --> Force new login */
                    this.br = new Browser();
                }
                br.setFollowRedirects(true);
                if (!account.getUser().matches(".+@.+\\..+")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBitte gib deine E-Mail Adresse ins Benutzername Feld ein!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlease enter your e-mail adress in the username field!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                getPage("https://" + this.getHost() + "/login");
                final String csrftoken = br.getRegex("name=\"csrf\\-token\" content=\"([^<>\"]*?)\"").getMatch(0);
                if (csrftoken == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else if ("pl".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBłąd wtyczki, skontaktuj się z Supportem JDownloadera!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                postPage("/login", "_csrf=" + Encoding.urlEncode(csrftoken) + "&LoginForm%5Bemail%5D=" + Encoding.urlEncode(account.getUser()) + "&LoginForm%5Bpassword%5D=" + Encoding.urlEncode(account.getPass()) + "&LoginForm%5BrememberMe%5D=0&LoginForm%5BrememberMe%5D=1");
                if (!this.br.containsHTML("class=\"status normal\"")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            } finally {
                br.setFollowRedirects(followsRedirect);
            }
        }
    }

    @SuppressWarnings({ "deprecation", "unchecked" })
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        if (isAPIAccount(account)) {
            this.loginAPI(account);
            /* At the moment we only support free accounts */
            if (true) {
                account.setProperty("free", true);
                maxPrem.set(ACCOUNT_FREE_MAXDOWNLOADS);
                account.setType(AccountType.FREE);
                /* free accounts can still have captcha */
                account.setMaxSimultanDownloads(maxPrem.get());
                account.setConcurrentUsePossible(true);
                ai.setStatus("Free Account");
                final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
                final LinkedHashMap<String, Object> result = (LinkedHashMap<String, Object>) entries.get("result");
                final LinkedHashMap<String, Object> traffic = (LinkedHashMap<String, Object>) result.get("traffic");
                /* TODO: Use these values correctly, then activate premium API usage */
                final String signup_at = (String) result.get("signup_at");
                final Long storage_left = JavaScriptEngineFactory.toLong(result.get("storage_left"), -1);
                // final Long storage_used = JavaScriptEngineFactory.toLong(result.get("storage_used"), -1);
                final Long traffic_left = JavaScriptEngineFactory.toLong(traffic.get("left"), -1);
                // final Long traffic_used_24h = JavaScriptEngineFactory.toLong(traffic.get("used_24h"), -1);
                ai.setCreateTime(TimeFormatter.getMilliSeconds(signup_at, "yyyy-MM-dd HH:mm:ss", Locale.ENGLISH));
                ai.setUsedSpace(storage_left);
                if (traffic_left == -1) {
                    ai.setUnlimitedTraffic();
                } else {
                    ai.setTrafficLeft(traffic_left);
                }
            } else {
                final String expire = br.getRegex("").getMatch(0);
                if (expire == null) {
                    final String lang = System.getProperty("user.language");
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername/Passwort oder nicht unterstützter Account Typ!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password or unsupported account type!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else {
                    ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", Locale.ENGLISH));
                }
                maxPrem.set(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(maxPrem.get());
                account.setConcurrentUsePossible(true);
                ai.setStatus("Premium Account");
            }
        } else {
            loginWebsite(account);
            /* At the moment we only support free accounts */
            if (true) {
                account.setProperty("free", true);
                maxPrem.set(ACCOUNT_FREE_MAXDOWNLOADS);
                account.setType(AccountType.FREE);
                /* free accounts can still have captcha */
                account.setMaxSimultanDownloads(maxPrem.get());
                account.setConcurrentUsePossible(true);
                ai.setStatus("Free Account");
            } else {
                final String expire = br.getRegex("").getMatch(0);
                if (expire == null) {
                    final String lang = System.getProperty("user.language");
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername/Passwort oder nicht unterstützter Account Typ!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password or unsupported account type!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else {
                    ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", Locale.ENGLISH));
                }
                maxPrem.set(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(maxPrem.get());
                account.setConcurrentUsePossible(true);
                ai.setStatus("Premium Account");
            }
        }
        ai.setUnlimitedTraffic();
        account.setValid(true);
        return ai;
    }

    private boolean isAPIAccount(final Account acc) {
        final boolean is_api_account = acc.getUser().matches("[a-f0-9]{16}") && acc.getPass().matches("[A-Za-z0-9]+") || !acc.getUser().matches(".+@.+");
        return is_api_account;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        loginAPI(account);
        br.setFollowRedirects(false);
        final boolean premium_not_yet_supported = true;
        if (account.getType() == AccountType.FREE) {
            doFree(link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink", account);
        } else {
            if (premium_not_yet_supported) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String dllink = this.checkDirectLink(link, "premium_directlink");
            if (dllink == null) {
                getPage(link.getDownloadURL());
                dllink = br.getRegex("").getMatch(0);
                if (dllink == null) {
                    logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
            if (dl.getConnection().getContentType().contains("html")) {
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            link.setProperty("premium_directlink", dllink);
            dl.startDownload();
        }
    }

    private void updatestatuscode() {
        final String status = PluginJSonUtils.getJsonValue(br, "status");
        if (status != null) {
            api_responsecode = Integer.parseInt(status);
        }
        this.api_msg = PluginJSonUtils.getJsonValue(br, "msg");
    }

    private void getPageAPI(final String url) throws Exception {
        super.getPage(url);
        this.updatestatuscode();
        handleAPIErrors();
    }

    private void handleAPIErrors() throws PluginException {
        final String status = PluginJSonUtils.getJsonValue(br, "status");
        switch (api_responsecode) {
        case 0:
            /* Everything okay */
            break;
        case 200:
            /* Everything okay */
            break;
        case 400:
            throw new PluginException(LinkStatus.ERROR_FATAL, "API error 400");
        case 403:
            /* E.g. "msg":"Download Ticket not valid any more" */
            if (br.containsHTML("the owner of this file doesn't allow API downloads")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "The owner of this file doesn't allow API downloads");
            }
            if ("Download Ticket not valid any more".equals(this.api_msg)) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API error 403 '" + this.api_msg + "'", 5 * 60 * 1000l);
            }
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        case 404:
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        case 405:
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        case 509:
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API error 509 'bandwidth usage too high (peak hours). out of capacity for non-browser downloads.'", 30 * 60 * 1000l);
        default:
            throw new PluginException(LinkStatus.ERROR_FATAL, "API error " + status);
        }
        if (super.br.getHttpConnection().getResponseCode() == 500) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 500", 10 * 60 * 1000l);
        } else if (super.br.getHttpConnection().getResponseCode() == 503) {
            /* E.g. overloaded server. */
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 503", 5 * 60 * 1000l);
        }
    }

    /**
     * If !enable_api_login this will act as if account == null as we cannot use the API download methods (which we use anyways) with
     * invalid logindata. As long as we stick to the API there should never be any major issues!
     */
    private String getAPILoginString(final Account account) {
        final String loginstring;
        if (account != null && enable_api_login) {
            loginstring = "login=" + Encoding.urlEncode(account.getUser()) + "&key=" + Encoding.urlEncode(account.getPass());
        } else {
            loginstring = "login=&key=";
        }
        return loginstring;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        /* workaround for free/premium issue on stable 09581 */
        return maxPrem.get();
    }

    /**
     * Handles pre download (pre-captcha) waittime. If WAITFORCED it ensures to always wait long enough even if the waittime RegEx fails.
     */
    private void waitTime(long timeBefore, int wait, final DownloadLink downloadLink) throws PluginException {
        int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
        wait -= passedTime;
        if (wait > 0) {
            sleep(wait * 1000l, downloadLink);
        }
    }

    private Browser prepBRAPI(final Browser br) {
        br.setAllowedResponseCodes(500);
        return br;
    }

    public static class OpenLoadIoAccountFactory extends MigPanel implements AccountBuilderInterface {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private final String      IDHELP           = "Enter your FTP Username/API Login";
        private final String      PINHELP          = "Enter your FTP Password/API Key";

        private String getPassword() {
            if (this.pass == null) {
                return null;
            }
            if (EMPTYPW.equals(new String(this.pass.getPassword()))) {
                return null;
            }
            return new String(this.pass.getPassword());
        }

        public boolean updateAccount(Account input, Account output) {
            boolean changed = false;
            if (!StringUtils.equals(input.getUser(), output.getUser())) {
                output.setUser(input.getUser());
                changed = true;
            }
            if (!StringUtils.equals(input.getPass(), output.getPass())) {
                output.setPass(input.getPass());
                changed = true;
            }
            return changed;
        }

        private String getUsername() {
            if (IDHELP.equals(this.name.getText())) {
                return null;
            }
            return this.name.getText();
        }

        private final ExtTextField     name;
        private final ExtPasswordField pass;
        private static String          EMPTYPW = "                 ";
        private final JLabel           idLabel;

        public OpenLoadIoAccountFactory(final InputChangedCallbackInterface callback) {
            super("ins 0, wrap 2", "[][grow,fill]", "");
            final String lang = System.getProperty("user.language");
            String usertext_finddata;
            String usertext_uid;
            if ("de".equalsIgnoreCase(lang)) {
                usertext_finddata = "<html>Klicke hier und dann auf \"User Settings\" um dein API loginname- und Passwort zu sehen:<br/></html>";
                usertext_uid = "FTP Username/API Login";
            } else {
                usertext_finddata = "<html>Click here and then on \"User Settings\"  to find your FTP Username/API Login AND FTP Password/API Key:<br/></html>";
                usertext_uid = "FTP Username/API Login";
            }
            add(new JLabel(usertext_finddata));
            add(new JLink("https://openload.co/account"));
            add(idLabel = new JLabel(usertext_uid));
            add(this.name = new ExtTextField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            });
            name.setHelpText(IDHELP);
            add(new JLabel("FTP Password/API Key:"));
            add(this.pass = new ExtPasswordField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            }, "");
            pass.setHelpText(PINHELP);
        }

        @Override
        public JComponent getComponent() {
            return this;
        }

        @Override
        public void setAccount(Account defaultAccount) {
            if (defaultAccount != null) {
                name.setText(defaultAccount.getUser());
                pass.setText(defaultAccount.getPass());
            }
        }

        @Override
        public boolean validateInputs() {
            final String userName = getUsername();
            if (userName == null) {
                idLabel.setForeground(Color.RED);
                return false;
            }
            idLabel.setForeground(Color.BLACK);
            return getPassword() != null;
        }

        @Override
        public Account getAccount() {
            return new Account(getUsername(), getPassword());
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}