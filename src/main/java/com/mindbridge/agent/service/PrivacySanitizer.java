package com.mindbridge.agent.service;

import org.springframework.stereotype.Service;

@Service
/**
 * 输入隐私脱敏服务。
 *
 * <p>对发送给模型和评估链路的文本做轻量脱敏，降低敏感标识进入上下文的概率。</p>
 */
public class PrivacySanitizer {

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text;
        // Phone numbers
        sanitized = sanitized.replaceAll("1[3-9]\\d{9}", "[手机号]");
        // Email addresses
        sanitized = sanitized.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[邮箱]");
        // Student ID / ID card patterns
        sanitized = sanitized.replaceAll("(?i)(学号|student\\s*id)[:：\\s]*[A-Za-z0-9_-]{6,20}", "$1:[学号]");
        sanitized = sanitized.replaceAll("(?i)(身份证|id\\s*card)[:：\\s]*[0-9xX]{15,18}", "$1:[证件号]");
        // Names
        sanitized = sanitized.replaceAll("我叫[\\u4e00-\\u9fa5]{2,4}", "我叫[姓名]");
        sanitized = sanitized.replaceAll("我是[\\u4e00-\\u9fa5]{2,4}", "我是[姓名]");
        // Address fragments (city/county patterns — common in Chinese addresses)
        sanitized = sanitized.replaceAll("[\\u4e00-\\u9fa5]{2,}(市|县|区|镇|村|路|街|号)(\\d+号?)?", "[地址]");
        // Raw input fragments longer than 100 chars (likely a repeated paste of the full user input)
        // This catches cases where the observation field contains the original user message verbatim
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 200) + "...[截断]";
        }
        return sanitized;
    }
}