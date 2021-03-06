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
package jd.plugins.decrypter;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.gui.IconKey;
import org.jdownloader.plugins.config.Group;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginHost;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.config.Type;
import org.jdownloader.scripting.JavaScriptEngineFactory;
import org.jdownloader.translate._JDT;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "ardmediathek.de", "rbb-online.de" }, urls = { "http://(?:www\\.)?(?:ardmediathek|mediathek\\.daserste)\\.de/.+|http://www\\.daserste\\.de/[^<>\"]+/(?:videos|videosextern)/[a-z0-9\\-]+\\.html", "http://(?:www\\.)?mediathek\\.rbb\\-online\\.de/tv/[^<>\"]+documentId=\\d+[^<>\"/]+bcastId=\\d+" })
public class Ardmediathek extends PluginForDecrypt {
    private static final String                 EXCEPTION_LINKOFFLINE          = "EXCEPTION_LINKOFFLINE";
    /* Constants */
    private static final String                 AGE_RESTRICTED                 = "(Diese Sendung ist für Jugendliche unter \\d+ Jahren nicht geeignet\\. Der Clip ist deshalb nur von \\d+ bis \\d+ Uhr verfügbar\\.)";
    private static final String                 type_unsupported               = "http://(www\\.)?ardmediathek\\.de/(tv/live\\?kanal=\\d+|dossiers/.*)";
    private static final String                 type_invalid                   = "http://(www\\.)?(ardmediathek|mediathek\\.daserste)\\.de/(download|livestream).+";
    private static final String                 type_ard_mediathek             = "http://(www\\.)?(ardmediathek|mediathek\\.daserste)\\.de/.+";
    private static final String                 type_ardvideo                  = "http://www\\.daserste\\.de/.+";
    private static final String                 type_rbb_mediathek             = "http://(?:www\\.)?mediathek\\.rbb\\-online\\.de/tv/[^<>\"]+documentId=\\d+[^<>\"/]+bcastId=\\d+";
    /* Variables */
    private final ArrayList<DownloadLink>       newRet                         = new ArrayList<DownloadLink>();
    private final HashMap<String, DownloadLink> bestMap                        = new HashMap<String, DownloadLink>();
    private String                              subtitleLink                   = null;
    private String                              parameter                      = null;
    private String                              title                          = null;
    private String                              date                           = null;
    private String                              date_formatted                 = null;
    ArrayList<DownloadLink>                     decryptedLinks                 = new ArrayList<DownloadLink>();
    private long                                existingQualityNum             = 0;
    private long                                selectedAndAvailableQualityNum = 0;

    public Ardmediathek(final PluginWrapper wrapper) {
        super(wrapper);
    }

    public static class Translation {
        public String getSubtitlesEnabled_label() {
            return _JDT.T.lit_add_subtitles();
        }

        public String getOnlyBestVideoQualityEnabled_label() {
            return _JDT.T.lit_add_only_the_best_video_quality();
        }
    }

    @PluginHost(host = "ardmediathek.de", type = Type.CRAWLER)
    public static interface RbbOnlineConfig extends ArdConfigInterface {
    }

    @PluginHost(host = "rbb-online.de", type = Type.CRAWLER)
    public static interface ArdConfigInterface extends PluginConfigInterface {
        public static final Group[]     GROUPS      = new Group[] { new Group("Video Settings", ".*VideoQuality.*", IconKey.ICON_VIDEO) };
        public static final Translation TRANSLATION = new Translation();

        @AboutConfig
        @DefaultBooleanValue(false)
        @Order(10)
        boolean isSubtitlesEnabled();

        void setSubtitlesEnabled(boolean b);

        @AboutConfig
        @DefaultBooleanValue(false)
        @Order(20)
        boolean isOnlyBestVideoQualityEnabled();

        void setOnlyBestVideoQualityEnabled(boolean b);

        @AboutConfig
        @DefaultBooleanValue(true)
        @Order(30)
        boolean isLowVideoQualityVersionEnabled();

        void setLowVideoQualityVersionEnabled(boolean b);

        @AboutConfig
        @DefaultBooleanValue(true)
        @Order(40)
        boolean isMediumVideoQualityVersionEnabled();

        void setMediumVideoQualityVersionEnabled(boolean b);

        @Order(50)
        @AboutConfig
        @DefaultBooleanValue(true)
        boolean isHighVideoQualityVersionEnabled();

        void setHighVideoQualityVersionEnabled(boolean b);

        @Order(60)
        @AboutConfig
        @DefaultBooleanValue(true)
        boolean isHDVideoQualityVersionEnabled();

        void setHDVideoQualityVersionEnabled(boolean b);

        @Order(70)
        @AboutConfig
        @DefaultBooleanValue(false)
        boolean isDossierAudioContentEnabled();

        void setDossierAudioContentEnabled(boolean b);

        @Order(80)
        @AboutConfig
        @DefaultBooleanValue(false)
        boolean isRTMPEnabled();

        void setRTMPEnabled(boolean b);

        @Order(90)
        @AboutConfig
        @DefaultBooleanValue(false)
        @DescriptionForConfigEntry("If enabled, the linkcheck will be faster, but the filesize might not be available before the actual download")
        boolean isFastLinkCheckEnabled();

        void setFastLinkCheckEnabled(boolean b);
    }

    @Override
    public Class<? extends ArdConfigInterface> getConfigInterface() {
        if ("ardmediathek.de".equalsIgnoreCase(getHost())) {
            return ArdConfigInterface.class;
        }
        return RbbOnlineConfig.class;
    }

    /**
     * Examples of other, unsupported linktypes:
     *
     * http://daserste.ndr.de/panorama/aktuell/Mal-eben-die-Welt-retten-Studie-belegt-Gefahren-durch-Voluntourismus-,volontourismus136.html
     *
     */
    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        String fsk = null;
        parameter = Encoding.htmlDecode(param.toString());
        if (parameter.matches(type_unsupported) || parameter.matches(type_invalid)) {
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        try {
            if (br.getURL().matches(type_ard_mediathek) || parameter.matches(type_rbb_mediathek)) {
                fsk = br.getRegex(AGE_RESTRICTED).getMatch(0);
                decryptMediathek();
            } else {
                decryptDasersteVideo();
            }
        } catch (final DecrypterException e) {
            try {
                if (e.getMessage().equals(EXCEPTION_LINKOFFLINE)) {
                    decryptedLinks.add(getOffline(parameter));
                    return decryptedLinks;
                }
            } catch (final Exception x) {
            }
            throw e;
        }
        if (existingQualityNum > 0 && selectedAndAvailableQualityNum == 0) {
            logger.info("User selected qualities that are not available --> Returning offline link");
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        if (decryptedLinks == null || decryptedLinks.size() == 0) {
            if (fsk != null) {
                logger.info("ARD-Mediathek: " + fsk);
                /** TODO: Check if this case still exists */
                return decryptedLinks;
            }
            logger.warning("Decrypter out of date for link: " + parameter);
            return null;
        }
        return decryptedLinks;
    }

    /* INFORMATION: network = akamai or limelight == RTMP */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void decryptMediathek() throws Exception {
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        String show = br.getRegex("name=\"dcterms\\.isPartOf\" content=\"([^<>\"]*?)\"").getMatch(0);
        title = br.getRegex("<meta name=\"dcterms\\.title\" content=\"([^\"]+)\"").getMatch(0);
        final String realBaseUrl = new Regex(br.getBaseURL(), "(^.*\\.de)").getMatch(0);
        String broadcastID;
        if (parameter.matches("http://(www\\.)?mediathek\\.daserste\\.de/topvideos/[a-z0-9\\-_]+")) {
            broadcastID = new Regex(parameter, "/topvideos/(\\d+)").getMatch(0);
        } else {
            // ardmediathek.de
            broadcastID = new Regex(br.getURL(), "(?:\\?|\\&)documentId=(\\d+)").getMatch(0);
            // mediathek.daserste.de
            if (broadcastID == null) {
                broadcastID = new Regex(br.getURL(), realBaseUrl + "/[^/]+/[^/]+/(\\d+)").getMatch(0);
            }
            if (broadcastID == null) {
                broadcastID = new Regex(br.getURL(), realBaseUrl + "/suche/(\\d+)").getMatch(0);
            }
        }
        if (broadcastID == null) {
            logger.info("ARDMediathek: MediaID is null! link offline?");
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        if (br.containsHTML("(<h1>Leider konnte die gew\\&uuml;nschte Seite<br />nicht gefunden werden\\.</h1>|Die angeforderte Datei existiert leider nicht)")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        this.date = br.getRegex("Video der Sendung vom (\\d{2}\\.\\d{2}\\.\\d{4})").getMatch(0);
        if (this.date == null) {
            this.date = br.getRegex("class=\"subtitle\">([^<>\"]*?) \\| ").getMatch(0);
        }
        final String original_ard_ID = broadcastID;
        if (title == null) {
            title = getTitle(br);
        }
        title = Encoding.htmlDecode(title).trim();
        title = encodeUnicode(title);
        if (show != null) {
            show = Encoding.htmlDecode(show).trim();
            show = encodeUnicode(show);
            title = show + " - " + title;
        }
        if (this.date != null) {
            this.date_formatted = formatDateArdMediathek(this.date);
            title = this.date_formatted + "_ard_" + title;
        }
        final Browser br = new Browser();
        setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage("http://www.ardmediathek.de/play/media/" + original_ard_ID + "?devicetype=pc&features=flash");
        /* No json --> No media to crawl! */
        if (!br.getHttpConnection().getContentType().contains("application/json")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        subtitleLink = getJson("_subtitleUrl", br.toString());
        if (subtitleLink != null && !subtitleLink.startsWith("http://")) {
            subtitleLink = "http://www.ardmediathek.de" + subtitleLink;
        }
        int streaming_type = 0;
        final String extension = ".mp4";
        final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        final ArrayList<Object> _mediaArray = (ArrayList) entries.get("_mediaArray");
        final LinkedHashMap<String, Object> _mediaArray_lastentry = (LinkedHashMap<String, Object>) _mediaArray.get(_mediaArray.size() - 1);
        final ArrayList<Object> mediaStreamArray = (ArrayList) _mediaArray_lastentry.get("_mediaStreamArray");
        for (final Object stream : mediaStreamArray) {
            String directlink = null;
            final LinkedHashMap<String, Object> streammap = (LinkedHashMap<String, Object>) stream;
            final String server = (String) streammap.get("_server");
            String network = (String) streammap.get("_cdn");
            /* Basically we change it for the filename */
            if (network == null) {
                network = "default_nonetwork";
            }
            /*
             * Sometimes one quality has multiple streams/sub-qualities --> Usually one qualities is missing in the main array so let's
             * "fix" that. Happens e.g. for documentId: 30102036
             */
            int quality = ((Number) streammap.get("_quality")).intValue();
            final Object stream_o = streammap.get("_stream");
            long filesize_max = -1;
            existingQualityNum++;
            if (!userWantsQuality(quality)) {
                continue;
            }
            if (stream_o instanceof ArrayList) {
                /*
                 * Array with even more qualities? Find the best - usually every array consists of max 2 entries so this should not take
                 * that much time. In total we will then always have (max) 4 qualities.
                 */
                final ArrayList<Object> streamArray = (ArrayList) stream_o;
                int counter = 0;
                long filesize_current = 0;
                URLConnectionAdapter con;
                for (final Object stream_single_o : streamArray) {
                    final String directlink_temp = (String) stream_single_o;
                    if (counter == 0) {
                        /* Make sure that, whatever happens, we get an http url! */
                        directlink = directlink_temp;
                    }
                    try {
                        con = br.openHeadConnection(directlink_temp);
                        filesize_current = con.getLongContentLength();
                        if (filesize_current > filesize_max) {
                            filesize_max = filesize_current;
                            directlink = directlink_temp;
                        }
                    } catch (final Throwable e) {
                    }
                    counter++;
                }
            } else {
                directlink = (String) streammap.get("_stream");
            }
            // rtmp --> hds or rtmp
            final boolean isRTMP = (server != null && !server.equals("") && server.startsWith("rtmp://")) && !directlink.startsWith("http");
            /* Server needed for rtmp links */
            if (!directlink.startsWith("http://") && isEmpty(server)) {
                continue;
            }
            // rtmp t=?
            if (isRTMP) {
                directlink = server + "@" + directlink.split("\\?")[0];
            }
            // /* Skip rtmp streams if user wants http only */
            // if (isRTMP && HTTP_ONLY) {
            // continue;
            // }
            if (!isHTTPUrl(directlink)) {
                continue;
            }
            addQuality(network, title, extension, isRTMP, directlink, quality, streaming_type, filesize_max);
            selectedAndAvailableQualityNum++;
        }
        findBEST();
        return;
    }

    /* INFORMATION: network = akamai or limelight == RTMP */
    private void decryptDasersteVideo() throws IOException, DecrypterException {
        final String xml_URL = parameter.replace(".html", "~playerXml.xml");
        setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(xml_URL);
        if (br.getHttpConnection().getResponseCode() == 404 || !br.getHttpConnection().getContentType().equals("application/xml")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        this.date = getXML("broadcastDate");
        title = getXML("shareTitle");
        if (this.title == null || this.date == null) {
            throw new DecrypterException("Decrypter broken");
        }
        title = Encoding.htmlDecode(title).trim();
        title = encodeUnicode(title);
        this.date_formatted = formatDateDasErste(this.date);
        title = this.date_formatted + "_daserste_" + title;
        /* TODO: Implement this */
        subtitleLink = null;
        int t = 0;
        final String extension = ".mp4";
        final String[] mediaStreamArray = br.getRegex("(<asset type=\".*?</asset>)").getColumn(0);
        for (final String stream : mediaStreamArray) {
            existingQualityNum++;
            final String assettype = new Regex(stream, "<asset type=\"([^<>\"]*?)\">").getMatch(0);
            final String server = null;
            final String network = "default";
            final int quality = this.convertASSETTYPEtoQuality(assettype);
            // rtmp --> hds or rtmp
            String directlink = getXML(stream, "fileName");
            final boolean isRTMP = (server != null && !server.equals("") && server.startsWith("rtmp://")) && !directlink.startsWith("http");
            /* Skip HDS */
            if (directlink.endsWith("manifest.f4m")) {
                continue;
            }
            /* Skip unneeded playlists */
            if ("default".equals(network) && directlink.endsWith("m3u")) {
                continue;
            }
            /* Server needed for rtmp links */
            if (!directlink.startsWith("http://") && isEmpty(server)) {
                continue;
            }
            directlink += "@";
            // rtmp t=?
            if (isRTMP) {
                directlink = server + "@" + directlink.split("\\?")[0];
            }
            /* Skip rtmp streams if user wants http only */
            if (isRTMP && PluginJsonConfig.get(getConfigInterface()).isRTMPEnabled()) {
                continue;
            }
            if (!userWantsQuality(Integer.valueOf(quality))) {
                continue;
            }
            addQuality(network, title, extension, isRTMP, directlink, quality, t, -1);
            selectedAndAvailableQualityNum++;
        }
        findBEST();
        return;
    }

    private boolean isHTTPUrl(final String directlink) {
        final boolean isHTTPUrl = directlink.startsWith("http") && !directlink.endsWith("m3u") && !directlink.endsWith("manifest.f4m");
        return isHTTPUrl;
    }

    private boolean userWantsQuality(final int quality) {
        ArdConfigInterface cfg = PluginJsonConfig.get(getConfigInterface());
        boolean best = cfg.isOnlyBestVideoQualityEnabled();
        switch (quality) {
        case 0:
            if ((cfg.isLowVideoQualityVersionEnabled() || best) == false) {
                return false;
            }
            return true;
        case 1:
            if ((cfg.isMediumVideoQualityVersionEnabled() || best) == false) {
                return false;
            }
            return true;
        case 2:
            if ((cfg.isHighVideoQualityVersionEnabled() || best) == false) {
                return false;
            }
            return true;
        case 3:
            if ((cfg.isHDVideoQualityVersionEnabled() || best) == false) {
                return false;
            }
            return true;
        default:
            /* E.g. unsupported */
            return false;
        }
    }

    /* Make fmt String out of quality Integer */
    private String getFMT(final int quality) {
        String fmt = null;
        switch (quality) {
        case 0:
            fmt = "low";
            break;
        case 1:
            fmt = "medium";
            break;
        case 2:
            fmt = "high";
            break;
        case 3:
            fmt = "hd";
            break;
        }
        return fmt;
    }

    /* Converts asset-type Strings from daserste.de video to the same Integer values used for their Mediathek * */
    private int convertASSETTYPEtoQuality(final String assettype) {
        int quality;
        if (assettype.equals("1.65 Web S VOD adaptive streaming") || assettype.contains("Prog 320x180")) {
            quality = 0;
        } else if (assettype.equals("1.63 Web M VOD adaptive streaming") || assettype.equals("1.24 Web M VOD") || assettype.equals("1.2.3.11.1 Web L")) {
            quality = 1;
        } else if (assettype.equals("1.71 ADS 4 VOD adaptive streaming") || assettype.equals("1.2.3.12.1 HbbTV 720x576")) {
            quality = 2;
        } else if (assettype.equals("1.69 Web L VOD adative streaming") || assettype.equals("1.2.3.12.2 Web L")) {
            quality = 3;
        } else {
            quality = -1;
        }
        return quality;
    }

    private void findBEST() {
        String lastQualityFMT = null;
        if (newRet.size() > 0) {
            if (PluginJsonConfig.get(getConfigInterface()).isOnlyBestVideoQualityEnabled()) {
                /* only keep best quality */
                DownloadLink keep = bestMap.get("hd");
                if (keep == null) {
                    lastQualityFMT = "HIGH";
                    keep = bestMap.get("high");
                }
                if (keep == null) {
                    lastQualityFMT = "MEDIUM";
                    keep = bestMap.get("medium");
                }
                if (keep == null) {
                    lastQualityFMT = "LOW";
                    keep = bestMap.get("low");
                }
                if (keep != null) {
                    newRet.clear();
                    newRet.add(keep);
                    /* We have to re-add the subtitle for the best quality if wished by the user */
                    if (PluginJsonConfig.get(getConfigInterface()).isSubtitlesEnabled() && subtitleLink != null && !isEmpty(subtitleLink)) {
                        final String plain_name = keep.getStringProperty("plain_name", null);
                        final String orig_streamingtype = keep.getStringProperty("streamingType", null);
                        final String linkid = plain_name + "_" + orig_streamingtype + "_subtitle";
                        final String subtitle_filename = plain_name + ".xml";
                        final DownloadLink dl_subtitle = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
                        dl_subtitle.setAvailable(true);
                        dl_subtitle.setFinalFileName(subtitle_filename);
                        dl_subtitle.setProperty("directURL", subtitleLink);
                        dl_subtitle.setProperty("directName", subtitle_filename);
                        dl_subtitle.setProperty("streamingType", "subtitle");
                        dl_subtitle.setProperty("mainlink", parameter);
                        dl_subtitle.setContentUrl(parameter);
                        dl_subtitle.setLinkID(linkid);
                        newRet.add(dl_subtitle);
                    }
                }
            }
            if (newRet.size() > 1) {
                FilePackage fp = FilePackage.getInstance();
                fp.setName(title);
                fp.addLinks(newRet);
            }
            decryptedLinks = newRet;
        }
    }

    @SuppressWarnings("deprecation")
    private void addQuality(final String network, final String title, final String extension, final boolean isRTMP, final String url, final int quality_int, final int streaming_type, final long filesize) {
        ArdConfigInterface cfg = PluginJsonConfig.get(getConfigInterface());
        final String fmt = getFMT(quality_int);
        final String quality_part = fmt.toUpperCase(Locale.ENGLISH) + "-" + network;
        final String plain_name = title + "@" + quality_part;
        final String full_name = plain_name + extension;
        String linkid = plain_name + "_" + streaming_type;
        final DownloadLink link = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
        /* RTMP links have no filesize anyways --> No need to check them in host plugin */
        if (isRTMP) {
            link.setAvailable(true);
        }
        link.setFinalFileName(full_name);
        link.setContentUrl(this.parameter);
        link.setLinkID(linkid);
        if (this.date != null) {
            link.setProperty("date", this.date);
        }
        link.setProperty("directURL", url);
        link.setProperty("directName", full_name);
        link.setProperty("plain_name", plain_name);
        link.setProperty("plain_quality_part", quality_part);
        link.setProperty("plain_name", plain_name);
        link.setProperty("plain_network", network);
        link.setProperty("directQuality", Integer.toString(quality_int));
        link.setProperty("streamingType", streaming_type);
        link.setProperty("mainlink", this.parameter);
        if (cfg.isFastLinkCheckEnabled()) {
            link.setAvailable(true);
        }
        if (filesize > -1) {
            link.setDownloadSize(filesize);
            link.setAvailable(true);
        }
        /* Add subtitle link for every quality so players will automatically find it */
        if (cfg.isSubtitlesEnabled() && subtitleLink != null && !isEmpty(subtitleLink)) {
            linkid = plain_name + "_subtitle_" + streaming_type;
            final String subtitle_filename = plain_name + ".xml";
            final DownloadLink dl_subtitle = createDownloadlink("http://ardmediathekdecrypted/" + System.currentTimeMillis() + new Random().nextInt(1000000000));
            /* JD2 only */
            dl_subtitle.setContentUrl(this.parameter);
            dl_subtitle.setLinkID(linkid);
            dl_subtitle.setAvailable(true);
            dl_subtitle.setFinalFileName(subtitle_filename);
            dl_subtitle.setProperty("directURL", subtitleLink);
            dl_subtitle.setProperty("directName", subtitle_filename);
            dl_subtitle.setProperty("streamingType", "subtitle");
            dl_subtitle.setProperty("mainlink", this.parameter);
            newRet.add(dl_subtitle);
        }
        final DownloadLink best = bestMap.get(fmt);
        if (best == null || link.getDownloadSize() > best.getDownloadSize()) {
            bestMap.put(fmt, link);
        }
        newRet.add(link);
    }

    private DownloadLink getOffline(final String parameter) {
        final DownloadLink dl = createDownloadlink("directhttp://" + parameter);
        dl.setAvailable(false);
        dl.setProperty("offline", true);
        return dl;
    }

    private String getTitle(final Browser br) {
        String title = br.getRegex("<(div|span) class=\"(MainBoxHeadline|BoxHeadline)\">([^<]+)</").getMatch(2);
        String titleUT = br.getRegex("<span class=\"(BoxHeadlineUT|boxSubHeadline)\">([^<]+)</").getMatch(1);
        if (titleUT == null) {
            titleUT = br.getRegex("<h3 class=\"mt\\-title\"><a>([^<>\"]*?)</a></h3>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("<title>ard\\.online \\- Mediathek: ([^<]+)</title>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("<h2>(.*?)</h2>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("class=\"mt\\-icon mt\\-icon_video\"></span><img src=\"[^\"]+\" alt=\"([^\"]+)\"").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("class=\"mt\\-icon mt\\-icon\\-toggle_arrows\"></span>([^<>\"]*?)</a>").getMatch(0);
        }
        if (title != null) {
            title = Encoding.htmlDecode(title + (titleUT != null ? "__" + titleUT.replaceAll(":$", "") : "").trim());
        }
        if (title == null) {
            title = "UnknownTitle_" + System.currentTimeMillis();
        }
        title = title.replaceAll("\\n|\\t|,", "").trim();
        return title;
    }

    private String getXML(final String parameter) {
        return getXML(this.br.toString(), parameter);
    }

    private String getXML(final String source, final String parameter) {
        return new Regex(source, "<" + parameter + "[^<]*?>([^<>]*?)</" + parameter + ">").getMatch(0);
    }

    private String getJson(final String parameter, final String source) {
        String result = new Regex(source, "\"" + parameter + "\":([0-9\\.]+)").getMatch(0);
        if (result == null) {
            result = new Regex(source, "\"" + parameter + "\":\"([^<>\"]*?)\"").getMatch(0);
        }
        return result;
    }

    private boolean isEmpty(String ip) {
        return ip == null || ip.trim().length() == 0;
    }

    private String formatDateArdMediathek(final String input) {
        final long date = TimeFormatter.getMilliSeconds(input, "dd.MM.yyyy", Locale.GERMAN);
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = input;
        }
        return formattedDate;
    }

    private String formatDateDasErste(String input) {
        /* 2015-06-23T20:15:00.000+02:00 --> 2015-06-23T20:15:00.000+0200 */
        input = input.substring(0, input.lastIndexOf(":")) + "00";
        final long date = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.GERMAN);
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = input;
        }
        return formattedDate;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}