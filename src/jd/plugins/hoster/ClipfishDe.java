//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.download.DownloadInterface;

import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "clipfish.de", "dooloop.tv" }, urls = { "http://(?:www\\.)?clipfish\\.de/(?:.*?channel/\\d+/video/\\d+|video/\\d+(?:/.+)?|special/.*?/video/\\d+|musikvideos/video/\\d+(?:/.+)?)", "https?://(?:www\\.)?dooloop\\.tv/video/\\d+/[A-Za-z0-9]+" }) 
public class ClipfishDe extends PluginForHost {

    public ClipfishDe(final PluginWrapper wrapper) {
        super(wrapper);
    }

    /* Tags: rtl-interactive.de */
    /* HbbTV also available */

    private final Pattern PATTERN_CAHNNEL_VIDEO  = Pattern.compile("http://[w\\.]+?clipfish\\.de/.*?channel/\\d+/video/(\\d+)");
    private final Pattern PATTERN_MUSIK_VIDEO    = Pattern.compile("http://[w\\.]+?clipfish\\.de/musikvideos/video/(\\d+)(/.+)?");
    private final Pattern PATTERN_STANDARD_VIDEO = Pattern.compile("http://[w\\.]+?clipfish\\.de/video/(\\d+)(/.+)?");
    private final Pattern PATTERN_SPECIAL_VIDEO  = Pattern.compile("http://[w\\.]+?clipfish\\.de/special/.*?/video/(\\d+)");
    private final Pattern PATTERN_FLV_FILE       = Pattern.compile("&url=(http://.+?\\....)&|<filename><\\!\\[CDATA\\[(.*?)\\]\\]></filename>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_TITEL          = Pattern.compile("<meta property=\"og:title\" content=\"(.+?)\"/>", Pattern.CASE_INSENSITIVE);

    private final Pattern PATTERN_DOLOOP_TV      = Pattern.compile("https?://(?:www\\.)?dooloop\\.tv/video/\\d+/[A-Za-z0-9]+");

    private final String  NEW_XMP_PATH           = "http://www.clipfish.de/devxml/videoinfo/";

    private String        dllink                 = null;
    private String        mediatype              = null;
    private String        videoid                = null;
    private String        flashplayer            = null;
    private boolean       serverIssue            = false;

    @SuppressWarnings("deprecation")
    @Override
    public void correctDownloadLink(final DownloadLink link) {
        if (link.getDownloadURL().startsWith("clipfish2")) {
            link.setUrlDownload(link.getDownloadURL().replaceAll("clipfish2://", "http://"));
        }
    }

    @Override
    public String getAGBLink() {
        return "http://www.clipfish.de/agb/";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @SuppressWarnings({ "deprecation", "unchecked" })
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        this.br.setAllowedResponseCodes(410);
        this.br.setFollowRedirects(true);
        dllink = null;
        mediatype = null;
        videoid = null;
        flashplayer = null;
        serverIssue = false;
        String filename = null;
        String title = null;
        String artist = null;
        String url_filename = null;
        String description = null;
        final String added_url = downloadLink.getDownloadURL();

        if (new Regex(added_url, PATTERN_DOLOOP_TV).matches()) {
            /*
             * doloop.tv simply embeds clipfish.de media but let's go the json-way this time - this might be more useful in the future for
             * all of their linktypes.
             */
            videoid = new Regex(added_url, "/video/(\\d+)").getMatch(0);
            url_filename = new Regex(added_url, "/video/\\d+/(.+)").getMatch(0);
            this.br.getPage("http://www.clipfish.de/devapi/playlist/similar/" + videoid + "/?format=json&apikey=dooloop&max-results=2");
            if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
            entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.walkJson(entries, "items/{0}");
            title = (String) entries.get("title");
            artist = (String) entries.get("mv_artist");
            description = (String) entries.get("descr");
            final String url_hds = (String) entries.get("media_videourl");
            if (title != null && artist != null) {
                filename = artist + " - " + title;
            } else {
                /* Fallback */
                filename = url_filename;
            }
            dllink = convertXToHttp(url_hds);
        } else {
            this.br.getPage(added_url);
            final Regex regexInfo = new Regex(this.br.toString(), PATTERN_TITEL);
            if (br.getURL().contains("clipfish.de/special/cfhome/home/")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (!br.containsHTML("CFAdBrandedPlayer\\.js") && !br.containsHTML("CFPlayerBasic(\\.min)?\\.js")) {
                logger.info("Link offline/unsupported");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (this.br.getHttpConnection().getResponseCode() == 410) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }

            String tmpStr = regexInfo.getMatch(0);
            if (tmpStr == null) {
                tmpStr = br.getRegex("<h2>([^<]+)</h2>").getMatch(0);
                if (tmpStr != null) {
                    tmpStr = br.getRegex("title=\"(" + tmpStr + "[^\"]+)\"").getMatch(0);
                }
            }
            if (tmpStr == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            filename = tmpStr.substring(0, tmpStr.lastIndexOf("-"));
            mediatype = tmpStr.substring(tmpStr.lastIndexOf("-") + 1, tmpStr.length()).toLowerCase(Locale.ENGLISH);
            if (filename == null || mediatype == null) {
                return null;
            }
            mediatype = mediatype.trim();

            if (new Regex(added_url, PATTERN_STANDARD_VIDEO).matches()) {
                videoid = new Regex(added_url, PATTERN_STANDARD_VIDEO).getMatch(0);
            } else if (new Regex(added_url, PATTERN_CAHNNEL_VIDEO).matches()) {
                videoid = new Regex(added_url, PATTERN_CAHNNEL_VIDEO).getMatch(0);
            } else if (new Regex(added_url, PATTERN_SPECIAL_VIDEO).matches()) {
                videoid = br.getRegex("vid\\s*:\\s*\"(\\d+)\"").getMatch(0);
            } else if (new Regex(added_url, PATTERN_MUSIK_VIDEO).matches()) {
                videoid = new Regex(added_url, PATTERN_MUSIK_VIDEO).getMatch(0);
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }

            this.flashplayer = br.getRegex("(clipfish_player_\\d+\\.swf)").getMatch(0);

            String spc = br.getRegex("specialId\\s*=\\s*(\\d+)").getMatch(0);
            int specialID = spc != null ? Integer.parseInt(spc) : -1;

            /*
             * Example http url: http://video.clipfish.de/media/<vid_url_id>/<videohash>.mp4 Videohashes can also be found in pic urls:
             * /autoimg/<videohash> /
             */
            /* Example HDS url (first req): http://hds.fra.clipfish.de/hds-vod-enc/media/<vid_url_id>/<videohash>.mp4.f4m */

            dllink = br.getRegex("videourl: \"(http[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                dllink = this.br.getRegex("var videourl[\t\n\r ]*?=[\t\n\r ]*?\"(http[^<>\"]*?)\";").getMatch(0);
            }
            if (dllink == null) {
                if (specialID >= 0) {
                    br.getPage(NEW_XMP_PATH + videoid + "/" + specialID + "/" + "?ts=" + System.currentTimeMillis());
                } else {
                    br.getPage(NEW_XMP_PATH + videoid + "/" + "?ts=" + System.currentTimeMillis());
                }
                String page = br.toString();
                dllink = getDllinkClipfish(page);
                if (dllink.startsWith("rtmp")) {
                    String dataPath = new Regex(dllink, "(media/.+?\\.mp4)").getMatch(0);
                    if (dataPath != null) {
                        dllink = "http://video.clipfish.de/" + dataPath;
                    }

                }
            }
        }

        filename = Encoding.htmlDecode(filename).trim();
        if (description != null && downloadLink.getComment() == null) {
            downloadLink.setComment(description);
        }

        String ext = null;
        if (dllink != null) {
            ext = dllink.substring(dllink.lastIndexOf(".") + 1, dllink.length());
            if (dllink.startsWith("rtmp")) {
                ext = new Regex(dllink, "(\\w+):media/").getMatch(0);
                ext = ext.length() > 3 ? null : ext;
                if (flashplayer != null) {
                    this.flashplayer = "http://www.clipfish.de/cfng/flash/" + this.flashplayer;
                }
            }
        }

        ext = ext == null || ext.equals("f4v") || ext.equals("") ? "mp4" : ext;
        downloadLink.setFinalFileName(filename + "." + ext);

        if (dllink != null && dllink.startsWith("http") && !dllink.contains(".hds.")) {
            final URLConnectionAdapter con = br.openHeadConnection(dllink);
            try {
                if (con.getResponseCode() == 200 && StringUtils.containsIgnoreCase(con.getContentType(), "video") && con.getCompleteContentLength() > 0) {
                    downloadLink.setVerifiedFileSize(con.getCompleteContentLength());
                } else {
                    serverIssue = true;
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        if (serverIssue) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else if ("".equals(dllink)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink.contains(".hds.")) {
            /* Usually encrypted hds */
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported protocol");
        }
        if (this.mediatype != null && !"video".equalsIgnoreCase(this.mediatype)) {
            // this will throw statserv errors.
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unsupported Media Type: " + this.mediatype);
        }
        if (dllink.startsWith("rtmp")) {
            if (this.flashplayer == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = new RTMPDownload(this, downloadLink, dllink);
            setupRTMPConnection(dllink, dl, this.flashplayer);
            ((RTMPDownload) dl).startDownload();
        } else {
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink);
            if (dl.getConnection().getLongContentLength() == 0) {
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    private String getDllinkClipfish(final String page) {
        String out = null;
        final String[] allContent = new Regex(page, PATTERN_FLV_FILE).getRow(0);
        for (final String c : allContent) {
            if (c != null) {
                out = c;
                break;
            }
        }
        return out;
    }

    private String convertXToHttp(final String other_protocol_url) {
        if (other_protocol_url == null) {
            return null;
        }
        final String url_http;
        final String important_part = new Regex(other_protocol_url, "/media/(.+\\.mp4)").getMatch(0);
        if (important_part != null) {
            url_http = "http://video.clipfish.de/media/" + important_part;
        } else {
            url_http = null;
        }
        return url_http;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    private void setupRTMPConnection(String stream, DownloadInterface dl, String fp) {
        jd.network.rtmp.url.RtmpUrlConnection rtmp = ((RTMPDownload) dl).getRtmpConnection();
        if (stream.contains("mp4:") && stream.contains("auth=")) {
            String pp = "mp4:" + stream.split("mp4:")[1];
            rtmp.setPlayPath(pp);
            rtmp.setUrl(stream.split("mp4:")[0]);
            rtmp.setApp("ondemand?ovpfv=2.0&" + new Regex(pp, "(auth=.*?)$").getMatch(0));
            rtmp.setSwfUrl(fp);
            rtmp.setResume(true);
        }
    }

}