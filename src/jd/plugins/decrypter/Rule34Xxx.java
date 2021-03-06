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

package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.HashSet;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Request;
import jd.nutils.encoding.Encoding;
import jd.nutils.encoding.HTMLEntities;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.SiteType.SiteTemplate;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;

/**
 *
 * @author raztoki
 *
 */
@DecrypterPlugin(revision = "$Revision: 32224 $", interfaceVersion = 3, names = { "rule34.xxx" }, urls = { "https?://(?:www\\.)?rule34\\.xxx/index\\.php\\?page=post\\&s=(view\\&id=\\d+|list\\&tags=.+)" })
public class Rule34Xxx extends PluginForDecrypt {

    private final String prefixLinkID = getHost().replaceAll("[\\.\\-]+", "") + "://";

    public Rule34Xxx(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = Encoding.htmlDecode(param.toString());
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">No Images Found<")) {
            decryptedLinks.add(createOfflinelink(parameter, "Offline Content"));
            return decryptedLinks;
        }
        if (parameter.contains("&s=view&")) {
            // from list to post page
            final String image = br.getRegex("<img[^>]+\\s+src=('|\")([^>]+)\\1 id=('|\")image\\3").getMatch(1);
            if (image != null) {
                // these should linkcheck as single event... but if from list its available = true.
                final String link = HTMLEntities.unhtmlentities(image);
                final DownloadLink dl = createDownloadlink(Request.getLocation(link, br.getRequest()));
                final String id = new Regex(parameter, "id=(\\d+)").getMatch(0);
                // set by decrypter from list, but not set by view!
                if (!StringUtils.equals(this.getCurrentLink().getSourceLink().getLinkID(), prefixLinkID + id)) {
                    dl.setLinkID(prefixLinkID + id);
                }
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
        }

        final String fpName = new Regex(parameter, "tags=(.+)&?").getMatch(0);
        FilePackage fp = null;
        if (fpName != null) {
            fp = FilePackage.getInstance();
            fp.setName(fpName);
        }
        final HashSet<String> loop = new HashSet<String>();
        loop: do {
            if (this.isAbort()) {
                logger.info("Decryption aborted by user");
                return decryptedLinks;
            }
            // from list to post page
            final String[] links = br.getRegex("<a id=\"p\\d+\" href=('|\")(index\\.php\\?page=post&(:?amp;)?s=view&(:?amp;)?id=\\d+)\\1").getColumn(1);
            if (links != null && links.length != 0) {
                for (String link : links) {
                    link = HTMLEntities.unhtmlentities(link);
                    final DownloadLink dl = createDownloadlink(Request.getLocation(link, br.getRequest()));
                    fp.add(dl);
                    // because of hundreds if links this is to speed shit up!
                    dl.setAvailable(true);
                    // we should set temp filename also
                    final String id = new Regex(link, "id=(\\d+)").getMatch(0);
                    dl.setLinkID(prefixLinkID + id);
                    dl.setName(id);
                    dl.setMimeHint(CompiledFiletypeFilter.ImageExtensions.BMP);
                    distribute(dl);
                    decryptedLinks.add(dl);
                }
            } else {
                // no links found we should break!
                return null;
            }
            final String nexts[] = br.getRegex("<a href=\"(\\?page=post&(:?amp;)?s=list&(:?amp;)?tags=[a-zA-Z0-9_\\-%]+&(:?amp;)?pid=\\d+)\"").getColumn(0);
            for (final String next : nexts) {
                if (loop.add(next)) {
                    br.getPage(HTMLEntities.unhtmlentities(next));
                    continue loop;
                }
            }
            break;
        } while (true);
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.Danbooru;
    }

}