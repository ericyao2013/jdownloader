//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Request;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.storage.simplejson.JSonArray;
import org.appwork.storage.simplejson.JSonFactory;
import org.appwork.storage.simplejson.JSonObject;
import org.appwork.storage.simplejson.JSonValue;
import org.appwork.storage.simplejson.ParserException;
import org.appwork.utils.net.httpconnection.HTTPConnection;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "account-pool.de" }, urls = { "REGEX_NOT_POSSIBLE_RANDOM-asdfasdfsadfsfsXXX" }) 
/**
 * Hoster plugin for the account-pool.de beta.
 *
 * @author julez
 */
public class AccountPool extends PluginForHost {

    private final String API_HOST                 = "https://api.account-pool.de";
    private final String TOS_URL                  = "https://account-pool.de/tos";

    private final String CLIENT_CONFIGURATION_KEY = "client_configuraion";

    @SuppressWarnings("deprecation")
    public AccountPool(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://account-pool.de");
    }

    @Override
    public String getAGBLink() {
        return TOS_URL;
    }

    @Override
    public boolean canHandle(DownloadLink downloadLink, Account account) {
        return account != null;
    }

    @Override
    public int getMaxSimultanDownload(DownloadLink link, Account account) {
        final Object value = this.getHosterSetting(account, link, "maximum_parallel_downloads");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return super.getMaxSimultanDownload(link, account);
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {

        AccountInfo ai = new AccountInfo();

        // Always Authenticate (in case the user changed username/password in the settings)
        LinkedHashMap<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("email", account.getUser());
        parameters.put("password", account.getPass());

        APIResponse response = this.unauthorizedApiRequest("/v1/auth", parameters);

        if (response.getResponseCode() == 401) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Wrong Login", PluginException.VALUE_ID_PREMIUM_DISABLE);
        } else if (response.getResponseCode() != 200) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unknown Error during authentication. Code: " + response.getResponseCode());
        }

        String authenticationToken = (String) ((JSonValue) response.getData().get("authentication_token")).getValue();
        account.setProperty("authentication_token", authenticationToken);

        // Query client configuration data
        APIResponse clientConfigurationResponse = this.authorizedApiGetRequest(account, "/v1/clientconfiguration/jdownloader");

        if (clientConfigurationResponse.getResponseCode() == 200) {

            // Prepare and store new configuration data
            Map<String, Object> clientConfiguration = (Map<String, Object>) this.convertJSonToSerializableData(clientConfigurationResponse.getData());
            if (clientConfiguration != null) {

                Map<String, Object> clientConfigurationRoot = (Map<String, Object>) clientConfiguration.get("client_configuration");
                if (clientConfigurationRoot != null) {

                    System.out.println("New Client Configuration: " + clientConfigurationRoot.toString());
                    ai.setProperty(CLIENT_CONFIGURATION_KEY, clientConfigurationRoot);

                    // Extract supported hosters
                    Map<String, Object> supportedHosters = (Map<String, Object>) clientConfigurationRoot.get("supported_hosters");

                    ArrayList<String> supportedHosts = new ArrayList<String>();
                    for (String hosterName : supportedHosters.keySet()) {
                        supportedHosts.add(hosterName);
                    }

                    ai.setMultiHostSupport(this, supportedHosts);
                    account.setConcurrentUsePossible(true);
                    account.setMaxSimultanDownloads(-1);
                    ai.setStatus("Good");
                    ai.setUnlimitedTraffic();

                    return ai;
                }
            }
        }
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unknown Error while fetching client configuration data. Code: " + response.getResponseCode());
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {

        LinkedHashMap<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("url", link.getDownloadURL());

        link.getLinkStatus().setStatusText("Step 1: Fetching Download Link");

        APIResponse response = this.authorizedApiPostRequest(account, "/v1/downloads", parameters);

        if (response.getResponseCode() != 200) {
            this.handleApiError(response, account);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }

        JSonObject download = (JSonObject) response.getData().get("download");
        JSonObject instructions = (JSonObject) download.get("instructions");

        // Download URL
        String downloadUrl = (String) ((JSonValue) instructions.get("url")).getValue();

        // Chunk Management
        int maxChunks = 1;
        JSonObject chunkSettings = (JSonObject) instructions.get("chunks");
        if (chunkSettings != null) {
            JSonValue maximumChunksSetting = (JSonValue) chunkSettings.get("maximum");
            if (maximumChunksSetting != null && maximumChunksSetting.getValue() instanceof Number) {
                maxChunks = ((Number) maximumChunksSetting.getValue()).intValue();
            }
        }

        // Resumable setting
        boolean resumable = false;
        JSonValue resumableSetting = (JSonValue) instructions.get("resumable");
        if (resumableSetting != null && resumableSetting.getValue() instanceof Boolean) {
            resumable = (Boolean) resumableSetting.getValue();
        }
        if (!resumable) {
            maxChunks = 1;
        }

        // Start the download task
        System.out.println("Downloading using URL " + downloadUrl + " (resumable: " + resumable + ", max Chunks: " + maxChunks + ")");
        link.getLinkStatus().setStatusText("Step 2: Starting actual Download");
        this.br = newBrowser();
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, downloadUrl, resumable, maxChunks);
        final int downloadResponseCode = dl.getConnection().getResponseCode();
        if (downloadResponseCode == 200) {
            if (dl.getConnection().isContentDisposition()) {
                dl.startDownload();
            } else {
                logger.info("Received invalid download (no content disposition)");
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else {
            br.followConnection();
            APIResponse apiResp = this.createApiResponse(dl.getConnection(), br);
            this.handleApiError(apiResp, account);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    /**
     * Handles a non-200 response code from the API by emitting the correct error (throwing a PluginException)
     *
     * @param response
     *            the received response
     * @param account
     *            the account for which the error occurred
     * @throws PluginException
     *             is thrown, no matter what response code was received
     */
    protected void handleApiError(final APIResponse response, final Account account) throws PluginException {

        // Try to get error message from the API
        String errorMessage = null;
        if (response.getData() != null) {
            JSonValue errorMessageValue = (JSonValue) response.getData().get("error");
            if (errorMessageValue != null) {
                errorMessage = (String) errorMessageValue.getValue();
            }
        }

        // Get default error message if API didn't return one
        if (errorMessage == null) {
            switch (response.getResponseCode()) {
            case 503:
                errorMessage = "Service temporarily in maintenance mode.";
            case 401:
            case 403:
                errorMessage = "Authentication error. Check your credentials.";
            case 400:
            case 404:
                errorMessage = "File not found, invalid download URL or hoster currently not supported.";
            case 420:
            case 429:
                errorMessage = "Too many parallel connections.";
            case 423:
                errorMessage = "Files for this hoster cannot be served at the moment. Maybe the account is blocked.";
            case 424:
            case 428:
                errorMessage = "Hoster specific error";
            case 509:
                errorMessage = "Bandwidth limit exceeded";
            default:
                errorMessage = "An unknown error occurred. Error code: " + response.getResponseCode();
            }
        }

        // Try to get the delay from the API
        int delay = -1;
        if (response.getData() != null) {
            JSonObject instructions = (JSonObject) response.getData().get("instructions");
            if (instructions != null) {
                JSonValue delayJsonValue = (JSonValue) instructions.get("delay");
                if (delayJsonValue != null && delayJsonValue.getValue() instanceof Number) {
                    delay = ((Number) delayJsonValue.getValue()).intValue() * 1000;
                }
            }
        }

        // Get default delay
        if (delay < 0) {
            switch (response.getResponseCode()) {
            case 503:
            case 420:
            case 429:
                delay = 10 * 60 * 1000;
                break;
            case 400:
            case 404:
                delay = 60 * 60 * 1000;
                break;
            case 423: // File locked
            case 424: // Hoster-specific error
                delay = 5 * 60 * 1000;
                break;
            default:
                delay = 15 * 60 * 1000;
            }
        }

        switch (response.getResponseCode()) {
        case 503:
        case 420:
        case 429:
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMessage, delay);
        case 401:
        case 403:
            throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMessage, PluginException.VALUE_ID_PREMIUM_DISABLE);
        case 509:
            throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMessage, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
        case 400:
        case 404:
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, errorMessage, delay);
        case 423:
        case 424:
        case 428:
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, errorMessage, delay);
        case 500:
        case 501:
        case 502:
        case 504:
        default:
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, errorMessage, delay);
        }
    }

    // API Communication
    /**
     * Sends an authorized POST request to the API.
     *
     * @param account
     *            Account for which the request should be sent
     * @param path
     *            API path
     * @param parameters
     *            Parameters which should be sent to the API
     * @param authenticationToken
     *            Authentication Token which should be used for the request
     * @return Returns the root json object which contains the data
     * @throws IOException
     * @throws ParserException
     */
    private APIResponse authorizedApiPostRequest(Account account, String path, LinkedHashMap<String, String> parameters) throws IOException {
        this.br = newBrowser();
        String authenticationToken = this.getAuthenticationToken(account);
        br.setHeader("X-Authentication-Token", authenticationToken);
        Request request = br.loadConnection(br.openPostConnection(API_HOST + path, parameters));

        return this.createApiResponse(request.getHttpConnection(), br);
    }

    /**
     * Sends an authorized GET request to the API.
     *
     * @param account
     *            Account for which the request should be sent
     * @param path
     *            API path
     * @param authenticationToken
     *            Authentication Token which should be used for the request
     * @return Returns the root json object which contains the data
     * @throws IOException
     * @throws ParserException
     */
    private APIResponse authorizedApiGetRequest(Account account, String path) throws IOException {
        this.br = newBrowser();
        String authenticationToken = this.getAuthenticationToken(account);
        br.setHeader("X-Authentication-Token", authenticationToken);
        Request request = br.loadConnection(br.openGetConnection(API_HOST + path));

        return this.createApiResponse(request.getHttpConnection(), br);
    }

    /**
     * Sends an unauthorized request to the API.
     *
     * @param path
     *            API path
     * @param parameters
     *            Parameters which should be sent to the API
     * @return Returns the root json object which contains the data
     * @throws IOException
     * @throws ParserException
     */
    private APIResponse unauthorizedApiRequest(String path, LinkedHashMap<String, String> parameters) throws IOException {
        this.br = newBrowser();
        Request request = br.loadConnection(br.openPostConnection(API_HOST + path, parameters));

        return this.createApiResponse(request.getHttpConnection(), br);
    }

    /**
     * Returns the API authentication token which was stored for the specified account.
     *
     * @param account
     *            Account for which the token should be retrieved
     * @return Authentication token for the specified account
     */
    private String getAuthenticationToken(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("an account must be specified in order to get the authenication token");
        }
        return (String) account.getProperty("authentication_token");
    }

    /**
     * Represents a response from the API.
     */
    protected class APIResponse {
        protected int        responseCode;
        protected JSonObject data;

        public APIResponse(int responseCode, JSonObject data) {
            this.responseCode = responseCode;
            this.data = data;
        }

        public int getResponseCode() {
            return this.responseCode;
        }

        public JSonObject getData() {
            return this.data;
        }

        public boolean isError() {
            return this.getResponseCode() != 200;
        }
    }

    /**
     * Instanciates a new APIResponse object for the specified connection and browser.
     *
     * @param con
     *            Connection
     * @param browser
     *            Browser
     * @return newly created APIResponse object
     */
    private APIResponse createApiResponse(HTTPConnection con, Browser browser) {

        JSonObject root = null;
        if (con.getContentType().contains("json")) {
            try {
                root = (JSonObject) new JSonFactory(browser.toString()).parse();
            } catch (ParserException e) {
            }
        }

        final int responseCode = con.getResponseCode();
        return new APIResponse(responseCode, root);
    }

    // Storage
    /**
     * Converts data returned from the JSON parser and standardizes it so it can be serialized.
     *
     * @param node
     *            JSON data to convert
     *
     * @return graph representing the JSON data. Consists of Maps, Lists and Objects.
     */
    private Object convertJSonToSerializableData(Object node) {

        if (node instanceof JSonValue) {
            Object value = ((JSonValue) node).getValue();

            if (value instanceof Integer) {
                Integer realVal = (Integer) value;
                return realVal;
            } else if (value instanceof Long) {
                Long realVal = (Long) value;
                return realVal.intValue();
            }

            return value;

        } else if (node instanceof JSonArray) {
            List<Object> newArray = new LinkedList<Object>();

            List<Object> array = (List<Object>) node;
            for (Object subNode : array) {
                newArray.add(this.convertJSonToSerializableData(subNode));
            }
            return newArray;
        } else if (node instanceof JSonObject) {
            Map<String, Object> newObject = new HashMap<String, Object>();

            Map<String, Object> object = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                newObject.put(entry.getKey(), this.convertJSonToSerializableData(entry.getValue()));
            }
            return newObject;
        } else {
            throw new IllegalArgumentException("node - not a JSON type");
        }
    }

    /**
     * Returns configuration from for the specified Account/Hoster combination.
     *
     * @param account
     *            Account for which the configuration should be retrieved.
     * @param link
     *            Link (used to extract the hoster) for which the configuration should be extracted. If null, a global setting will be
     *            returned instead of a hoster specific one.
     * @param key
     *            Name of the setting which should be retrieved.
     *
     * @return retrieved configuration value
     */
    private Object getHosterSetting(Account account, DownloadLink link, String key) {
        AccountInfo ai = account.getAccountInfo();
        Map<String, Object> clientConfiguration = (Map<String, Object>) ai.getProperty(CLIENT_CONFIGURATION_KEY);
        if (clientConfiguration == null) {
            return null;
        }
        if (link == null) {
            return clientConfiguration.get(key);
        } else {
            Map<String, Object> allHostersSettings = (Map<String, Object>) clientConfiguration.get("supported_hosters");
            if (allHostersSettings != null) {
                Map<String, Object> hosterSettings = (Map<String, Object>) allHostersSettings.get(link.getHost());
                if (hosterSettings != null) {
                    return hosterSettings.get(key);
                }
            }
        }
        return null;
    }

    // Helper Methods
    private Browser newBrowser() {
        br = new Browser();
        br.setCookiesExclusive(true);
        br.getHeaders().put("Accept", "application/json");
        br.getHeaders().put("User-Agent", "JDownloader");
        br.setCustomCharset("utf-8");
        br.setConnectTimeout(15 * 1000);
        br.setReadTimeout(30 * 1000);
        br.setAllowedResponseCodes(100, 200, 201, 202, 203, 301, 302, 303, 304, 400, 401, 402, 403, 404, 405, 406, 408, 409, 410, 416, 420, 423, 424, 428, 500, 501, 502, 503, 504, 509);
        return br;
    }

    // Unused Stuff
    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception, PluginException {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 0;
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink link) throws PluginException {
        return AvailableStatus.UNCHECKABLE;
    }
}