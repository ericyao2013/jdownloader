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

import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;

/**
 *
 *
 *
 */
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "xxx-blog.to" }, urls = { "http://(www\\.)?xxx-blog\\.to/((share|sto|com-|u|filefactory/|relink/)[\\w\\./\\-]+|.*?\\.html|(blog|typ|genre)/(?:[^/]+/){2})" }) 
public class XXXBlg extends antiDDoSForDecrypt {

    public XXXBlg(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected boolean useRUA() {
        return true;
    }

    @Override
    protected Browser prepBrowser(final Browser prepBr, final String host) {
        if (!(browserPrepped.containsKey(prepBr) && browserPrepped.get(prepBr) == Boolean.TRUE)) {
            super.prepBrowser(prepBr, host);
            /* define custom browser headers and language settings */
        }
        return prepBr;
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        br = new Browser();
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        final ArrayList<String> pwList = new ArrayList<String>();
        pwList.add("xxx-blog.dl.am");
        pwList.add("xxx-blog.org");
        pwList.add("xxx-blog.to");
        br.setFollowRedirects(true);
        // crapola
        br.setCookie(Browser.getHost(parameter), "hasVsitedSite", "yes");
        getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">403 Forbidden<") || br.containsHTML("No htmlCode read") || br.getURL().equals("http://xxx-blog.to/")) {
            decryptedLinks.add(createOfflinelink(parameter, new Regex(parameter, "xxx-blog\\.to/(.+)").getMatch(0), null));
            return decryptedLinks;
        }

        if (parameter.matches("http://(www\\.)?xxx\\-blog\\.to/((share|sto|com\\-|u|filefactory/|relink/)[\\w\\./\\-]+|.*?\\.html)")) {
            DownloadLink dLink;
            if (br.getRedirectLocation() != null) {
                dLink = createDownloadlink(br.getRedirectLocation());
            } else {
                Form form = br.getForm(0);
                if (form == null) {
                    return null;
                }
                dLink = createDownloadlink(form.getAction(null));
                if (!parameter.matches(this.getSupportedLinks().toString())) {
                    decryptedLinks.add(createDownloadlink(parameter));
                }
            }
            dLink.setSourcePluginPasswordList(pwList);
            decryptedLinks.add(dLink);

        } else {
            String fpname = br.getRegex("<title>(.*?)\\| XXX\\-Blog").getMatch(0);
            if (fpname == null) {
                fpname = br.getRegex("rel=\"bookmark\" title=\"(.*?)\"").getMatch(0);
            }
            String pagepiece = br.getRegex("<div class=\"entry\">(.*?)</article>").getMatch(0);
            if (pagepiece == null) {
                pagepiece = br.getRegex("<div class=\"entry\">(.+)\\s+</div>\\s+<br />").getMatch(0);
                if (pagepiece == null) {
                    pagepiece = br.getRegex("<table class=\"dltable\"(.*?)class='easySpoilerConclude'").getMatch(0);
                    if (pagepiece == null) {
                        pagepiece = br.getRegex("class='easySpoilerTitleA'(.*?)class='easySpoilerConclude'").getMatch(0);
                        if (pagepiece == null) {
                            pagepiece = this.br.getRegex("<strong>(.*?)</a></strong></p>").getMatch(0);
                            if (pagepiece == null) {
                                logger.warning("pagepiece is null, using full html code!");
                                pagepiece = br.toString();
                            }
                        }
                    }
                }
            }
            final String[] regexes = { "\"http://xxx\\-blog\\.to/download/\\?(http[^<>\"]*?)\"", "<a href=\"(http[^<>\"]*?)\" target=\"_blank\"" };
            for (final String currentRegex : regexes) {
                final String[] links = new Regex(pagepiece, currentRegex).getColumn(0);
                if (links == null || links.length == 0) {
                    continue;
                }
                for (final String link : links) {
                    if (link.matches("http://(www\\.)?xxx\\-blog\\.to/((share|sto|com\\-|u|filefactory/|relink/)[\\w\\./\\-]+|.*?\\.html|(blog|typ|genre)(?:[^/]+/){2})")) {
                        continue;
                    }
                    final DownloadLink dlink = createDownloadlink(link);
                    dlink.setSourcePluginPasswordList(pwList);
                    decryptedLinks.add(dlink);
                }
            }
            if (decryptedLinks.size() == 0) {
                logger.info("Failed to find any downloadable content");
                return decryptedLinks;
            }

            if (fpname != null) {
                FilePackage fp = FilePackage.getInstance();
                fp.setName(fpname.trim());
                fp.addLinks(decryptedLinks);
            }

        }
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}