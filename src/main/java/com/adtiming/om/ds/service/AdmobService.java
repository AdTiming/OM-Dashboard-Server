// Copyright 2020 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.adtiming.om.ds.service;

import com.adtiming.om.ds.dao.OmAdnetworkAppMapper;
import com.adtiming.om.ds.dao.ReportAdnetworkAccountMapper;
import com.adtiming.om.ds.dto.Response;
import com.adtiming.om.ds.model.OmAdnetworkApp;
import com.adtiming.om.ds.model.ReportAdnetworkAccount;
import com.adtiming.om.ds.util.HttpConnMgr;
import com.alibaba.fastjson.JSONObject;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.adsense.AdSense;
import com.google.api.services.adsense.model.Account;
import com.google.api.services.adsense.model.Accounts;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;

@Service
public class AdmobService extends BaseService {

    protected static final Logger log = LogManager.getLogger();

    @Resource
    private OmAdnetworkAppMapper omAdnetworkAppMapper;

    @Resource
    private ReportAdnetworkAccountMapper reportAdnAccountMapper;

    @Value("${om.adc.domain}")
    private String adcDomain;

    @Value("${admob.client_id}")
    public String admobClientId;

    @Value("${admob.client_secret}")
    public String admobClientSecret;

    /**
     * Get admob auth grant url
     *
     * @param adnAppId
     */
    public Response getAdmobAuthUrl(Integer adnAppId) {
        String url;
        try {
            if (adnAppId == null) {
                return Response.RES_PARAMETER_ERROR;
            }
            OmAdnetworkApp adnetworkApp = this.omAdnetworkAppMapper.selectByPrimaryKey(adnAppId);
            if (adnetworkApp == null) {
                log.error("AdNetworkAppId {} does not existed", adnAppId);
                return Response.RES_DATA_DOES_NOT_EXISTED;
            }
            ReportAdnetworkAccount account = reportAdnAccountMapper.selectByPrimaryKey(adnetworkApp.getReportAccountId());
            String authKey = account.getAuthKey();
            if (StringUtils.isBlank(authKey)) {
                return new Response().code(500).msg("Account Auth Key is null");
            }
            String reqUrl = String.format("%s/admob/auth/getUrl?adnAppId=%s", adcDomain, adnAppId);
            HttpGet httpGet = new HttpGet(reqUrl);
            HttpResponse response = HttpConnMgr.getHttpClient().execute(httpGet);
            url = EntityUtils.toString(response.getEntity());
        } catch (Exception e) {
            return new Response().code(500).msg("OAuth Access error," + e.getMessage());
        }
        return Response.buildSuccess(url);
    }

    public String getAdmobPublisherId(String clientId, String clientSecret, String refreshToken) throws IOException {
        NetHttpTransport transport = new NetHttpTransport.Builder().build();
        GoogleCredential credential = new GoogleCredential.Builder()
                .setJsonFactory(JacksonFactory.getDefaultInstance())
                .setTransport(transport)
                .setClientSecrets(clientId, clientSecret)
                .build()
                .setRefreshToken(refreshToken);
        boolean isRefreshed = credential.refreshToken();

        if (!isRefreshed) {
            throw new IOException("Refresh credential failed.");
        }
        AdSense adsense = new AdSense.Builder(transport,
                JacksonFactory.getDefaultInstance(), credential)
                .setApplicationName("google_api")
                .build();
        Accounts accounts = adsense.accounts().list()
                .setMaxResults(1)
                .execute();
        if (accounts.getItems() != null && !accounts.getItems().isEmpty()) {
            Account account = accounts.getItems().get(0);
            return account.getId();
        } else {
            throw new IOException("No accounts found.");
        }
    }

    public Response saveTokenByCode(Integer accountId, String authCode) throws Exception {
        ReportAdnetworkAccount adnetworkAccount = this.reportAdnAccountMapper.selectByPrimaryKey(accountId);
        if (adnetworkAccount == null) {
            log.error("Admob ReportAdnetworkAccount {} does not existed", accountId);
            return Response.RES_DATA_DOES_NOT_EXISTED;
        }
        // Exchange auth code for access token
        //GoogleClientSecrets clientSecrets = getClientSecrets();
        GoogleTokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                new NetHttpTransport.Builder().build(),
                JacksonFactory.getDefaultInstance(),
                "https://oauth2.googleapis.com/token",//clientSecrets.getDetails().getTokenUri(),
                admobClientId,//clientSecrets.getDetails().getClientId(),
                admobClientSecret,//clientSecrets.getDetails().getClientSecret(),
                authCode,
                "postmessage")  // Specify the same redirect URI that you use with your web
                // app. If you don't have a web version of your app, you can
                // specify an empty string.
                .execute();
        //GoogleIdToken idToken = tokenResponse.parseIdToken();
        String refreshToken = tokenResponse.getRefreshToken();
        log.info("Account id {} get refresh token {}", accountId, refreshToken);
        if (StringUtils.isBlank(refreshToken)) {
            throw new Exception("This Account is granted!");
        }
        String publisherId = getAdmobPublisherId(admobClientId, admobClientSecret, refreshToken);
        adnetworkAccount.setUserId(publisherId);
        adnetworkAccount.setAdnAppToken(refreshToken);
        int dbResult = this.reportAdnAccountMapper.updateByPrimaryKeySelective(adnetworkAccount);
        if (dbResult <= 0) {
            log.error("Update adnetworkAccount {} failed", JSONObject.toJSON(adnetworkAccount));
            return Response.RES_FAILED;
        }
        JSONObject result = new JSONObject();
        result.put("pubId", publisherId);
        return Response.buildSuccess(result);
    }

    /**
     * Get admob auth grant url
     */
    public Object admobAuthCallBack(String userId, String code, String error, String authKey) {
        String message = "Successfully Granted!";
        try {
            String url = String.format("%s/admob/auth/callback/%s?user_id=%s&code=%s&error=%s", adcDomain, authKey, userId, code, error);
            HttpGet httpGet = new HttpGet(url);
            HttpResponse response = HttpConnMgr.getHttpClient().execute(httpGet);
            HttpEntity entity = response.getEntity();
            String content = EntityUtils.toString(entity);
            if (StringUtils.isNotBlank(content)) {
                message = "Authorization failed";
            }
        } catch (Exception e) {
            message = "Authorization failed, reason:" + e.getMessage();
        }
        /*String content =  "<html><head><title>OAuth 2.0 Authentication Token Recieved</title></head><body>"
                + message
                + "</body></HTML>";*/
        return Response.buildSuccess(message);
    }
}
