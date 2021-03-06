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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

// http://tvthek,orf.at/live/... --> HDS
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "tvthek.orf.at" }, urls = { "http://(www\\.)?tvthek\\.orf\\.at/(?:index\\.php/)?(programs?|topic)/.+" })
public class ORFMediathekDecrypter extends PluginForDecrypt {

    private static final String Q_SUBTITLES   = "Q_SUBTITLES";
    private static final String Q_BEST        = "Q_BEST_2";
    private static final String Q_LOW         = "Q_LOW";
    private static final String Q_MEDIUM      = "Q_MEDIUM";
    private static final String Q_HIGH        = "Q_HIGH";
    private static final String Q_VERYHIGH    = "Q_VERYHIGH";
    private static final String HTTP_STREAM   = "HTTP_STREAM";
    private boolean             BEST          = false;

    private static final String TYPE_TOPIC    = "http://(www\\.)?tvthek\\.orf\\.at/topic/.+";
    private static final String TYPE_PROGRAMM = "http://(www\\.)?tvthek\\.orf\\.at/programs?/.+";

    public ORFMediathekDecrypter(final PluginWrapper wrapper) {
        super(wrapper);
    }

    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString().replace("/index.php/", "/");
        this.br.setAllowedResponseCodes(500);
        this.br.setLoadLimit(this.br.getLoadLimit() * 4);
        final SubConfiguration cfg = SubConfiguration.getConfig("orf.at");
        BEST = cfg.getBooleanProperty(Q_BEST, false);
        br.getPage(parameter);
        int status = br.getHttpConnection().getResponseCode();
        if (status == 301 || status == 302) {
            br.setFollowRedirects(true);
            if (br.getRedirectLocation() != null) {
                parameter = br.getRedirectLocation();
                br.getPage(parameter);
            }
        } else if (status != 200) {
            final DownloadLink link = this.createOfflinelink(parameter);
            decryptedLinks.add(link);
            return decryptedLinks;
        }
        if (br.containsHTML("(404 \\- Seite nicht gefunden\\.|area_headline error_message\">Keine Sendung vorhanden<)") || !br.containsHTML("class=\"video_headline\"") || status == 404 || status == 500) {
            final DownloadLink link = this.createOfflinelink(parameter);
            link.setName(new Regex(parameter, "tvthek\\.orf\\.at/programs/(.+)").getMatch(0));
            decryptedLinks.add(link);
            return decryptedLinks;
        }

        decryptedLinks.addAll(getDownloadLinks(parameter, cfg));

        if (decryptedLinks == null || decryptedLinks.size() == 0) {
            if (parameter.matches(TYPE_TOPIC)) {
                logger.warning("MAYBE Decrypter out of date for link: " + parameter);
            } else {
                logger.warning("Decrypter out of date for link: " + parameter);
            }
            return null;
        }
        return decryptedLinks;
    }

    @SuppressWarnings({ "deprecation", "unchecked", "unused", "rawtypes" })
    private ArrayList<DownloadLink> getDownloadLinks(final String data, final SubConfiguration cfg) {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String nicehost = new Regex(data, "http://(?:www\\.)?([^/]+)").getMatch(0);
        final String decryptedhost = "http://" + nicehost + "decrypted";

        try {
            final String json = br.getRegex("initializeAdworx\\((.*?)\\);\n").getMatch(0);
            final String video_id = new Regex(data, "(\\d+)$").getMatch(0);
            String xmlData = br.getRegex("ORF\\.flashXML\\s*=\\s*\'([^\']+)\';").getMatch(0);

            if (xmlData != null || json != null) {
                Map<String, HashMap<String, String>> mediaEntries = new TreeMap<String, HashMap<String, String>>();
                HashMap<String, String> mediaEntry = null;
                String quality = null, key = null, title = null;
                /* jsonData --> HashMap */
                ArrayList<Object> ressourcelist = (ArrayList) JavaScriptEngineFactory.jsonToJavaObject(json);
                LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) ressourcelist.get(ressourcelist.size() - 1);
                entries = (LinkedHashMap<String, Object>) entries.get("values");
                ressourcelist = (ArrayList) entries.get("segments");

                final String site_title = getTitle(br);
                String fpName = site_title;
                if (video_id != null) {
                    fpName += "_" + video_id;
                }
                String extension = ".mp4";
                if (br.getRegex("new MediaCollection\\(\"audio\",").matches()) {
                    extension = ".mp3";
                }

                ArrayList<DownloadLink> part = new ArrayList<DownloadLink>();

                for (final Object segmento : ressourcelist) {
                    final LinkedHashMap<String, Object> entry = (LinkedHashMap<String, Object>) segmento;
                    final LinkedHashMap<String, Object> playlist_data = (LinkedHashMap<String, Object>) entry.get("playlist_data");
                    final ArrayList<Object> sources_video = (ArrayList) playlist_data.get("sources");
                    ArrayList<Object> subtitle_list = null;
                    final Object sources_subtitle_o = playlist_data.get("subtitles");

                    final String encrypted_id = (String) entry.get("encrypted_id");
                    final String decrypted_id = Encoding.Base64Decode(encrypted_id);
                    final String description = (String) entry.get("description");
                    String titlethis = (String) entry.get("title");
                    if (titlethis == null) {
                        titlethis = description;
                    }
                    if (titlethis != null && titlethis.length() > 80) {
                        titlethis = titlethis.substring(0, 80);
                    }

                    String vIdTemp = "";
                    String bestFMT = null;
                    String subtitle = null;
                    boolean is_best = false;
                    DownloadLink bestQuality = null;
                    DownloadLink bestSubtitle = null;
                    FilePackage fp = null;
                    if (titlethis != null) {
                        fp = FilePackage.getInstance();
                        fp.setName(fpName);
                    }

                    for (final Object sourceo : sources_video) {
                        subtitle = null;
                        is_best = false;
                        final LinkedHashMap<String, Object> entry_source = (LinkedHashMap<String, Object>) sourceo;
                        final Iterator<Entry<String, Object>> it = entry_source.entrySet().iterator();
                        while (it.hasNext()) {
                            final Entry<String, Object> entry_entry = it.next();
                            final String ky = entry_entry.getKey();
                            if (entry_entry.getValue() instanceof String) {
                                try {
                                    final String value = (String) entry_entry.getValue();
                                    mediaEntry.put(ky, value);
                                } catch (final Throwable e) {
                                }
                            }
                        }

                        /* Backward compatibility with xml method */
                        final String url = (String) entry_source.get("src");
                        String fmt = (String) entry_source.get("quality");
                        final String protocol = (String) entry_source.get("protocol");
                        final String delivery = (String) entry_source.get("delivery");
                        // final String subtitleUrl = (String) entry_source.get("SubTitleUrl");
                        if (isEmpty(url) && isEmpty(fmt) && isEmpty(protocol) && isEmpty(delivery)) {
                            continue;
                        }
                        if (sources_subtitle_o != null) {
                            /* [0] = .srt, [1] = WEBVTT .vtt */
                            subtitle_list = (ArrayList) sources_subtitle_o;
                            if (subtitle_list.size() > 0) {
                                subtitle = (String) JavaScriptEngineFactory.walkJson(subtitle_list.get(1), "src");
                            } else {
                                subtitle = (String) JavaScriptEngineFactory.walkJson(subtitle_list.get(0), "src");
                            }
                        }
                        long filesize = 0;

                        // available protocols: http, rtmp, rtsp, hds, hls
                        if (!"http".equals(protocol) || !"progressive".equals(delivery)) {
                            continue;
                        }
                        /* Leave this in in case we want to support rtmp versions again in the future. */
                        // if (cfg.getBooleanProperty(HTTP_STREAM, false) && "rtmp".equals(protocol)) {
                        // continue;
                        // }

                        if (url == null || isEmpty(fmt)) {
                            continue;
                        }

                        final String selector = protocol + delivery;

                        String fileName = titlethis + "@" + selector;
                        if (video_id != null) {
                            fileName += "_" + video_id;
                        }
                        if (decrypted_id != null) {
                            fileName += "_" + decrypted_id;
                        }
                        fileName += "@" + humanReadableQualityIdentifier(fmt.toUpperCase(Locale.ENGLISH).trim());
                        fileName = fileName.replaceAll("\"", "");
                        fileName = fileName.replaceAll(":\\s|\\s\\|\\s", " - ").trim();

                        final String ext_from_directurl = getFileNameExtensionFromString(url);
                        if (ext_from_directurl.length() == 4) {
                            extension = ext_from_directurl;
                        }
                        fmt = humanReadableQualityIdentifier(fmt.toUpperCase(Locale.ENGLISH).trim());

                        boolean sub = true;
                        if (fileName.equals(vIdTemp)) {
                            sub = false;
                        }

                        if ("VERYHIGH".equals(fmt) || BEST) {
                            /*
                             * VERYHIGH is always available but is not always REALLY available which means we have to check this here and
                             * skip it if needed! Filesize is also needed to find BEST quality.
                             */
                            boolean veryhigh_is_available = true;
                            try {
                                final URLConnectionAdapter con = br.openHeadConnection(url);
                                if (!con.isOK()) {
                                    veryhigh_is_available = false;
                                } else {
                                    /*
                                     * Basically we already did the availablecheck here so for this particular quality we don't have to do
                                     * it again in the linkgrabber!
                                     */
                                    filesize = con.getLongContentLength();
                                }
                                try {
                                    con.disconnect();
                                } catch (final Throwable e) {
                                }
                            } catch (final Throwable e) {
                                veryhigh_is_available = false;
                            }
                            if (!veryhigh_is_available) {
                                continue;
                            }
                        }
                        /* best selection is done at the end */
                        if ("LOW".equals(fmt)) {
                            if ((cfg.getBooleanProperty(Q_LOW, true) || BEST) == false) {
                                continue;
                            } else {
                                fmt = "LOW";
                            }
                        } else if ("MEDIUM".equals(fmt)) {
                            if ((cfg.getBooleanProperty(Q_MEDIUM, true) || BEST) == false) {
                                continue;
                            } else {
                                fmt = "MEDIUM";
                            }
                        } else if ("HIGH".equals(fmt)) {
                            if ((cfg.getBooleanProperty(Q_HIGH, true) || BEST) == false) {
                                continue;
                            } else {
                                fmt = "HIGH";
                            }
                        } else if ("VERYHIGH".equals(fmt)) {
                            if ((cfg.getBooleanProperty(Q_VERYHIGH, true) || BEST) == false) {
                                continue;
                            } else {
                                fmt = "VERYHIGH";
                            }
                        } else {
                            if (unknownQualityIdentifier(fmt)) {
                                logger.info("ORFMediathek Decrypter: unknown quality identifier --> " + fmt);
                                logger.info("Link: " + data);
                            }
                            continue;
                        }
                        final String final_filename_without_extension = fileName + (protocol != null ? "_" + protocol : "");
                        final String final_filename_video = final_filename_without_extension + extension;
                        final DownloadLink link = createDownloadlink(decryptedhost + System.currentTimeMillis() + new Random().nextInt(1000000000));

                        link.setFinalFileName(final_filename_video);
                        link.setContentUrl(data);
                        link.setProperty("directURL", url);
                        link.setProperty("directName", final_filename_video);
                        link.setProperty("directQuality", fmt);
                        link.setProperty("mainlink", data);
                        if (protocol == null && delivery == null) {
                            link.setAvailable(true);
                            link.setProperty("streamingType", "rtmp");
                        } else {
                            link.setProperty("streamingType", protocol);
                            link.setProperty("delivery", delivery);
                            if (filesize > 0) {
                                link.setAvailable(true);
                                link.setDownloadSize(filesize);
                            } else if (!"http".equals(protocol)) {
                                link.setAvailable(true);
                            }
                        }
                        if (fp != null) {
                            link._setFilePackage(fp);
                        }
                        link.setLinkID(decrypted_id + "_" + fmt);

                        if (bestQuality == null || link.getDownloadSize() > bestQuality.getDownloadSize()) {
                            bestQuality = link;
                            is_best = true;
                        }
                        part.add(link);
                        if (sub) {
                            if (cfg.getBooleanProperty(Q_SUBTITLES, false)) {
                                if (!isEmpty(subtitle)) {
                                    final String final_filename_subtitle = final_filename_without_extension + ".srt";
                                    final DownloadLink subtitle_downloadlink = createDownloadlink(decryptedhost + System.currentTimeMillis() + new Random().nextInt(1000000000));
                                    subtitle_downloadlink.setProperty("directURL", subtitle);
                                    subtitle_downloadlink.setProperty("directName", final_filename_subtitle);
                                    subtitle_downloadlink.setProperty("streamingType", "subtitle");
                                    subtitle_downloadlink.setProperty("mainlink", data);
                                    subtitle_downloadlink.setAvailable(true);
                                    subtitle_downloadlink.setFinalFileName(final_filename_subtitle);
                                    subtitle_downloadlink.setContentUrl(data);
                                    subtitle_downloadlink.setLinkID(decrypted_id + "_" + fmt + "_subtitle");
                                    if (fp != null) {
                                        subtitle_downloadlink._setFilePackage(fp);
                                    }
                                    part.add(subtitle_downloadlink);
                                    if (is_best) {
                                        bestSubtitle = subtitle_downloadlink;
                                    }
                                    vIdTemp = fileName;
                                }
                            }
                        }
                    }
                    if (BEST) {
                        ret.add(bestQuality);
                        if (bestSubtitle != null) {
                            ret.add(bestSubtitle);
                        }
                    } else {
                        ret.addAll(part);
                    }
                    part.clear();
                }
            }
        } catch (final Throwable e) {
            e.printStackTrace();
            logger.severe(e.getMessage());
        }
        return ret;
    }

    private boolean isEmpty(String ip) {
        return ip == null || ip.trim().length() == 0;
    }

    private String getTitle(Browser br) {
        String title = br.getRegex("<title>(.*?)\\s*\\-\\s*ORF TVthek</title>").getMatch(0);
        String titleUT = br.getRegex("<span class=\"BoxHeadlineUT\">([^<]+)</").getMatch(0);
        if (title == null) {
            title = br.getRegex("<meta property=\"og:title\" content=\"([^\"]+)\"").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("\'playerTitle\':\\s*\'([^\'])\'$").getMatch(0);
        }
        if (title != null) {
            title = Encoding.htmlDecode(title + (titleUT != null ? "__" + titleUT.replaceAll(":$", "") : "").trim());
        }
        if (title == null) {
            title = "UnknownTitle_" + System.currentTimeMillis();
        }
        return title;
    }

    private String humanReadableQualityIdentifier(String s) {
        final String humanreabable;
        if ("Q1A".equals(s)) {
            humanreabable = "LOW";
        } else if ("Q4A".equals(s)) {
            humanreabable = "MEDIUM";
        } else if ("Q6A".equals(s)) {
            humanreabable = "HIGH";
        } else if ("Q8C".equals(s)) {
            humanreabable = "VERYHIGH";
        } else {
            humanreabable = null;
        }
        return humanreabable;
    }

    private boolean unknownQualityIdentifier(String s) {
        if (s.matches("(DESCRIPTION|SMIL|SUBTITLEURL|DURATION|TRANSCRIPTURL|TITLE|QUALITY|QUALITY_STRING|PROTOCOL|TYPE|DELIVERY)")) {
            return false;
        }
        return true;
    }

    private String decodeUnicode(String s) {
        Pattern p = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
        String res = s;
        Matcher m = p.matcher(res);
        while (m.find()) {
            res = res.replaceAll("\\" + m.group(0), Character.toString((char) Integer.parseInt(m.group(1), 16)));
        }
        return res;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}