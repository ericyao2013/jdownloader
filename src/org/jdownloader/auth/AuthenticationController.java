package org.jdownloader.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jd.http.Browser;

import org.appwork.shutdown.ShutdownController;
import org.appwork.shutdown.ShutdownEvent;
import org.appwork.shutdown.ShutdownRequest;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.StringUtils;
import org.appwork.utils.event.predefined.changeevent.ChangeEvent;
import org.appwork.utils.event.predefined.changeevent.ChangeEventSender;

import org.appwork.utils.logging2.LogSource;
import org.jdownloader.auth.AuthenticationInfo.Type;
import org.jdownloader.logging.LogController;

public class AuthenticationController {
    private static final AuthenticationController INSTANCE = new AuthenticationController();

    /**
     * get the only existing instance of AuthenticationController. This is a singleton
     *
     * @return
     */
    public static AuthenticationController getInstance() {
        return AuthenticationController.INSTANCE;
    }

    private final AuthenticationControllerSettings         config;
    private final CopyOnWriteArrayList<AuthenticationInfo> list;
    private final ChangeEventSender                        eventSender = new ChangeEventSender();
    private final LogSource                                logger;

    /**
     * Create a new i nstance of AuthenticationController. This is a singleton class. Access the only existing instance by using
     * {@link #getInstance()}.
     */
    private AuthenticationController() {
        logger = LogController.getInstance().getLogger(AuthenticationController.class.getName());
        config = JsonConfig.create(AuthenticationControllerSettings.class);
        CopyOnWriteArrayList<AuthenticationInfo> list = cleanup(config.getList());
        if (list == null) {
            list = new CopyOnWriteArrayList<AuthenticationInfo>();
        }
        this.list = list;
        ShutdownController.getInstance().addShutdownEvent(new ShutdownEvent() {
            @Override
            public void onShutdown(final ShutdownRequest shutdownRequest) {
                config.setList(AuthenticationController.this.list);
            }

            @Override
            public long getMaxDuration() {
                return 0;
            }

            @Override
            public String toString() {
                return "ShutdownEvent: Save AuthController";
            }
        });
    }

    public ChangeEventSender getEventSender() {
        return eventSender;
    }

    public java.util.List<AuthenticationInfo> list() {
        return list;
    }

    /* remove invalid entries...without hostmask or without logins */
    private CopyOnWriteArrayList<AuthenticationInfo> cleanup(List<AuthenticationInfo> input) {
        if (input == null || input.size() == 0) {
            return null;
        }
        CopyOnWriteArrayList<AuthenticationInfo> ret = new CopyOnWriteArrayList<AuthenticationInfo>();
        for (AuthenticationInfo item : input) {
            if (StringUtils.isEmpty(item.getHostmask())) {
                continue;
            }
            if (StringUtils.isEmpty(item.getPassword()) && StringUtils.isEmpty(item.getPassword())) {
                continue;
            }
            ret.add(item);
        }
        return ret;
    }

    public void add(AuthenticationInfo a) {
        if (a != null && list.addIfAbsent(a)) {
            config.setList(list);
            eventSender.fireEvent(new ChangeEvent(this));
        }
    }

    public void remove(AuthenticationInfo a) {
        if (a != null && list.remove(a)) {
            config.setList(list);
            eventSender.fireEvent(new ChangeEvent(this));
        }
    }

    public void remove(java.util.List<AuthenticationInfo> selectedObjects) {
        if (selectedObjects != null && list.removeAll(selectedObjects)) {
            config.setList(list);
            eventSender.fireEvent(new ChangeEvent(this));
        }
    }

    public List<Login> getSortedLoginsList(String url) {
        if (StringUtils.isEmpty(url)) {
            return null;
        }
        AuthenticationInfo.Type type = null;
        if (url.startsWith("ftp")) {
            type = Type.FTP;
        } else if (url.startsWith("http")) {
            type = Type.HTTP;
        } else {
                  org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().info("Unknown Protocoll: " + url);
            return null;
        }
        final ArrayList<AuthenticationInfo> possibleInfos = new ArrayList<AuthenticationInfo>();

        String urlHost = Browser.getHost(url, true);
        for (AuthenticationInfo info : list) {
            if (!info.isEnabled()) {
                continue;
            }
            final String authHost = info.getHostmask();
            if (info.getType().equals(type) && !StringUtils.isEmpty(authHost)) {
                boolean contains = false;
                if (authHost.length() > urlHost.length()) {
                    /* hostMask of AuthenticationInfo is longer */
                    contains = authHost.contains(urlHost);
                } else {
                    /* hostMask of urlHost is longer */
                    contains = urlHost.contains(authHost);
                }
                if (contains) {
                    possibleInfos.add(info);

                }
            }
        }
        try {
            Collections.sort(possibleInfos, new Comparator<AuthenticationInfo>() {

                @Override
                public int compare(AuthenticationInfo o1, AuthenticationInfo o2) {
                    int ret = Integer.compare(o2.getHostmask().length(), o1.getHostmask().length());
                    if (ret == 0) {
                        ret = Long.compare(o2.getLastValidated(), o1.getLastValidated());
                    }
                    if (ret == 0) {
                        ret = Long.compare(o2.getCreated(), o1.getCreated());
                    }
                    return ret;
                }
            });

        } catch (Throwable e) {
            logger.log(e);
        }
        final ArrayList<Login> ret = new ArrayList<Login>();
        for (AuthenticationInfo info : possibleInfos) {
            ret.add(new Login(info.getUsername(), info.getPassword()));
        }
        return ret;
    }

    public Login getBestLogin(String url) {
        List<Login> ret = getSortedLoginsList(url);
        if (ret != null && ret.size() > 0) {
            return ret.get(0);
        }
        return null;
    }

    public void invalidate(Login login, String url) {
    }

    public void validate(Login login, String url) {

        if (StringUtils.isEmpty(url)) {
            return;
        }
        AuthenticationInfo.Type type = null;
        if (url.startsWith("ftp")) {
            type = Type.FTP;
        } else if (url.startsWith("http")) {
            type = Type.HTTP;
        } else {
                  org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().info("Unknown Protocoll: " + url);
            return;
        }

        String urlHost = Browser.getHost(url, true);
        for (AuthenticationInfo info : list) {
            if (!info.isEnabled()) {
                continue;
            }
            String authHost = info.getHostmask();
            if (info.getType().equals(type) && !StringUtils.isEmpty(authHost)) {
                boolean contains = false;
                if (authHost.length() > urlHost.length()) {
                    /* hostMask of AuthenticationInfo is longer */
                    contains = authHost.contains(urlHost);
                } else {
                    /* hostMask of urlHost is longer */
                    contains = urlHost.contains(authHost);
                }
                if (contains) {
                    if (StringUtils.equals(info.getUsername(), login.getUsername()) && StringUtils.equals(info.getPassword(), login.getPassword())) {
                        info.setLastValidated(System.currentTimeMillis());

                    }

                }
            }
        }

    }
}
