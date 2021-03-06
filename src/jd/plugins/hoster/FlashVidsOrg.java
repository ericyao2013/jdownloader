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

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

//Same code as  YamiVideoCom, DivxHostedCom, RocketFilesNet, FlashVidsOrg
@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "flashvids.org" }, urls = { "http://(www\\.)?flashvids\\.org/video/[a-z0-9]+" }) 
public class FlashVidsOrg extends PluginForHost {

    public FlashVidsOrg(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://flashvids.org/contact";
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (br.containsHTML("404 \\- Page Cannot Be Found|>The page you are looking for cannot be found|>To find the missing content|class=\"error message\"")) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        String filename = br.getRegex("<h2 style=\"font\\-size:20px\">([^<>\"]*?)</h2>").getMatch(0);
        if (filename == null) filename = br.getRegex("<title>([^<>\"]*?) video</title>").getMatch(0);
        if (filename == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        link.setName(Encoding.htmlDecode(filename.trim()) + ".flv");
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        if (br.containsHTML("I AM HUMAN")) br.postPage(downloadLink.getDownloadURL(), "submit_human=I+AM+HUMAN");
        br.postPageRaw("http://" + this.getHost() + "/Xajax/saveaction/", "xjxfun=load_player_eng&xjxr=" + System.currentTimeMillis() + "&xjxargs[]=S" + new Regex(downloadLink.getDownloadURL(), "([a-z0-9]{12})$").getMatch(0) + "&xjxargs[]=N3&xjxargs[]=Sip");
        final String dllink = br.getRegex("\\&file=(http://[a-z0-9]+\\." + this.getHost() + "/[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, true, -5);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            if (br.containsHTML(">404 Not Found<")) throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 10 * 60 * 1000l);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}