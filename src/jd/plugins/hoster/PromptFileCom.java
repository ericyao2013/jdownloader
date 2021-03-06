//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "promptfile.com" }, urls = { "http://(www\\.)?promptfile\\.com/l/[A-Z0-9]+\\-[A-Z0-9]+" })
public class PromptFileCom extends PluginForHost {

    public PromptFileCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://www.promptfile.com/");
    }

    @Override
    public String getAGBLink() {
        return "http://www.promptfile.com/terms";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME               = true;
    private static final int     FREE_MAXCHUNKS            = 0;
    private static final int     FREE_MAXDOWNLOADS         = 20;
    private static final boolean ACCOUNT_FREE_RESUME       = true;
    private static final int     ACCOUNT_FREE_MAXCHUNKS    = 0;
    private static final int     ACCOUNT_FREE_MAXDOWNLOADS = 20;

    /* don't touch the following! */
    private static AtomicInteger maxPrem                   = new AtomicInteger(1);

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (br.getURL().equals("http://www.promptfile.com/?404") || this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Regex fInfo = br.getRegex("<span style=\"text\\-decoration:none;cursor:text;\" title=\"([^<>\"]*?)\">([^<>\"]*?) \\((\\d+(\\.\\d{1,2})? [A-Za-z]{2,5})\\)</span>");
        final String filename = fInfo.getMatch(0);
        final String filesize = fInfo.getMatch(2);
        if (filename == null || filesize == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setName(Encoding.htmlDecode(filename.trim()));
        link.setDownloadSize(SizeFormatter.getSize(filesize));
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        final boolean failed_once = downloadLink.getBooleanProperty("failed_once", false);
        String dllink = checkDirectLink(downloadLink, directlinkproperty);
        if (dllink == null) {
            // use form
            final Form chash = br.getForm(0);
            // final String cHash = br.getRegex("(?:id|name)\\s*=\\s*\"chash\"\\s+value\\s*=\\s*\"([a-z0-9]+)\"").getMatch(0);
            // if (cHash == null) {
            // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            // }
            // br.postPage(br.getURL(), "chash=" + cHash);
            if (chash == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String obstruction = chash.getRegex("onclick='\\$\\(\"#chash\"\\)\\.val\\(\"(.*?)\"\\+\\$\\(\"#chash\"\\)\\.val\\(\\)\\)").getMatch(0);
            if (obstruction != null) {
                chash.put(chash.getInputFields().get(0).getKey(), obstruction + chash.getInputFields().get(0).getValue());
            }
            /* 2016-09-01: This cookie is important - without it we will get a (database) error from the server. */
            this.br.setCookie(this.getHost(), "Upgrade-Insecure-Requests", "1");
            br.submitForm(chash);
            if (failed_once) {
                /*
                 * It failed once via downloadlink ? Sometimes the downloadlink is broken but if we have a stream, we might be able to
                 * download that instead (lower quality / filesize).
                 */
                dllink = br.getRegex("clip: \\{\\s+url: \\'(http://(www\\.)?promptfile\\.com/file/[A-Za-z0-9=]+)(\\'|\")").getMatch(0);
            }
            if (dllink == null) {
                dllink = br.getRegex("<a href=\"(http://(www\\.)?promptfile\\.com/file/[A-Za-z0-9=]{90,}+)\"").getMatch(0);
            }
            if (dllink == null) {
                dllink = br.getRegex(">Download<.+\\s+.+\\s+<a href=\"(http://www\\.promptfile\\.com[^<>\"]+)\"").getMatch(0);
                logger.info("dllink = " + dllink);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 60 * 60 * 1000l);
            }
            br.followConnection();
            if (br.containsHTML("file unavailable|file not ready")) {
                if (failed_once) {
                    /* Cycle through download/stream-download! --> Reset our errorhandling-boolean for broken downloadlinks */
                    downloadLink.setProperty("failed_once", false);
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error: File unavailable", 30 * 60 * 1000l);
                }
                downloadLink.setProperty("failed_once", true);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Downloadlink broken, trying to download stream instead if possible");
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* Reset our errorhandling-boolean for broken downloadlinks */
        downloadLink.setProperty("failed_once", false);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    private static final String MAINPAGE = "http://promptfile.com";
    private static Object       LOCK     = new Object();

    /* boolean FORCE is ignored */
    @SuppressWarnings("unchecked")
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?>) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(MAINPAGE, key, value);
                        }
                        /* Needed to avoid login captcha prompt when user is away */
                        br.getPage("http://www.promptfile.com/");
                        if (br.containsHTML("id=\"logout_btn\"")) {
                            return;
                        }
                        br.clearCookies(MAINPAGE);
                    }
                }
                br.setFollowRedirects(false);
                // br.getPage("http://www.promptfile.com/");
                final DownloadLink dummyLink = new DownloadLink(this, "Account", "promptfile.com", "http://promptfile.com", true);
                final String code = getCaptchaCode("http://www.promptfile.com/securimage_show.php?sid=0." + System.currentTimeMillis(), dummyLink);
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("http://www.promptfile.com/actions.php", "action=login&data%5Blogin%5D=" + Encoding.urlEncode(account.getUser()) + "&data%5Bpassword%5D=" + Encoding.urlEncode(account.getPass()) + "&data%5Bcode%5D=" + Encoding.urlEncode(code));
                if (!br.containsHTML("\"status\":1") || br.getCookie(MAINPAGE, "pfauth") == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername, Passwort oder Login-Captcha!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password or login-captcha!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                // Save cookies
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(MAINPAGE);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        /* Only free accounts are supported at the moment */
        maxPrem.set(ACCOUNT_FREE_MAXDOWNLOADS);
        account.setType(AccountType.FREE);
        /* free accounts of this host can't have captchas */
        account.setMaxSimultanDownloads(maxPrem.get());
        account.setConcurrentUsePossible(true);
        ai.setStatus("Registered (free) user");
        account.setValid(true);
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(account, false);
        br.setFollowRedirects(false);
        br.getPage(link.getDownloadURL());
        String dllink = this.checkDirectLink(link, "account_free_directlink");
        if (dllink == null) {
            dllink = br.getRegex("\"(https?://(www\\.)?promptfile\\.com/file/[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty("account_free_directlink", dllink);
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        /* workaround for free/premium issue on stable 09581 */
        return maxPrem.get();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

}