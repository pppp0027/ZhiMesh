package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;

public class SearchEngineUtil {

    public static boolean checkGoogleCountry(String country) {
        boolean result = false;
        for (String googleCountry : ZhiMeshConstant.SearchEngineName.GOOGLE_COUNTRIES) {
            if (googleCountry.equalsIgnoreCase(country)) {
                result = true;
                break;
            }
        }
        return result;
    }

    public static boolean checkGoogleLanguage(String language) {
        boolean result = false;
        for (String googleCountry : ZhiMeshConstant.SearchEngineName.GOOGLE_LANGUAGES) {
            if (googleCountry.equalsIgnoreCase(language)) {
                result = true;
                break;
            }
        }
        return result;
    }
}
