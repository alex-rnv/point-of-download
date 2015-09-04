package com.alexrnv.pod.bean;

import io.vertx.core.MultiMap;

import static java.lang.System.getProperty;
import static java.util.Objects.requireNonNull;

/**
 * Date: 9/4/2015
 * Time: 4:33 PM
 *
 * Author: Alex
 */
public class BeanUtil {

    public static String resolvePath(String path) {
        return requireNonNull(path).replace("%t", getProperty("java.io.tmpdir") + "/");
    }

    public static String toString(MultiMap mm) {
        if(mm.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        mm.forEach(e -> sb.append(e.getKey()).append(":'").append(e.getValue()).append("',"));
        sb.setCharAt(sb.length()-1, '}');
        return sb.toString();
    }
}
