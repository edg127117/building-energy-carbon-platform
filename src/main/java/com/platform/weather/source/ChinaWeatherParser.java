package com.platform.weather.source;

import com.platform.weather.source.WeatherSourceModels.FetchResult;
import com.platform.weather.source.WeatherSourceModels.Product;
import com.platform.weather.source.WeatherSourceModels.Request;
import com.platform.weather.source.WeatherSourceModels.Sample;
import com.platform.weather.source.WeatherSourceModels.Source;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** 将中国天气网七日页面转换为北京时间日预报；缺失温度保留为空，由查询层决定整批回退。 */
final class ChinaWeatherParser {
    static final String VERSION = "weather-com-cn-7d-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern DAY = Pattern.compile("^(\\d{1,2})日.*$");
    private static final Pattern TEMP = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChinaWeatherParser.class);

    FetchResult parse(String body, Request request, Instant fetchedAt) {
        try {
            return parseValidated(body, request, fetchedAt);
        } catch (WeatherSourceException error) {
            if (error.getCode() == WeatherSourceException.Code.DATE_AMBIGUOUS) {
                Document page = Jsoup.parse(body);
                Element anchor = page.getElementById("zs_7d_update_time");
                Element week = page.getElementById("7d");
                String headers = week == null ? "MISSING" : week.select("ul.t > li h1").stream()
                        .limit(7).map(node -> safeDateEvidence(node.text())).collect(java.util.stream.Collectors.joining("|"));
                log.warn("Weather page date rejected parser={} fetchedAt={} detail={} anchor={} headers={}",
                        VERSION, fetchedAt, error.getDetail(),
                        anchor == null ? "MISSING" : safeDateEvidence(anchor.attr("value")), headers);
            }
            throw error;
        }
    }

    /** 仅保留有界日期字符作为失败样本，防止外部页面正文或换行进入日志。 */
    private static String safeDateEvidence(String text) {
        String bounded = text.substring(0, Math.min(text.length(), 64));
        return bounded.replaceAll("[^0-9年月日 :./-]", "_");
    }
    private FetchResult parseValidated(String body, Request request, Instant fetchedAt) {
        Document document = Jsoup.parse(body);
        Element updated = document.getElementById("zs_7d_update_time");
        if (updated == null || !updated.hasAttr("value")) {
            throw dateAmbiguous(WeatherSourceException.Detail.ANCHOR_MISSING);
        }
        LocalDate anchor;
        try {
            LocalDateTime published = LocalDateTime.parse(updated.attr("value"),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"));
            anchor=published.toLocalDate();
        } catch (DateTimeParseException exception) {
            throw dateAmbiguous(WeatherSourceException.Detail.ANCHOR_FORMAT);
        }
        if (!anchor.equals(fetchedAt.atZone(ZONE).toLocalDate())) {
            throw dateAmbiguous(WeatherSourceException.Detail.ANCHOR_DAY_MISMATCH);
        }
        Element forecast = document.getElementById("7d");
        Elements days = forecast == null ? new Elements() : forecast.select("ul.t > li");
        if (days.size() != 7) {
            throw structureError();
        }
        List<Sample> samples = new ArrayList<>(7);
        for (int index = 0; index < days.size(); index++) {
            Element item = days.get(index);
            LocalDate date = anchor.plusDays(index);
            validateDay(item, date);
            Element weather = item.selectFirst("p.wea");
            Element temperature = item.selectFirst("p.tem");
            Element wind = item.selectFirst("p.win");
            if (weather == null || temperature == null) {
                throw structureError();
            }
            Element highNode = temperature.selectFirst("span");
            Element lowNode = temperature.selectFirst("i");
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("temperature_2m_max", parseTemperature(highNode));
            values.put("temperature_2m_min", parseTemperature(lowNode));
            Map<String, String> text = new LinkedHashMap<>();
            text.put("weather", weather.text().trim());
            text.put("wind", wind==null?"":wind.text().trim());
            text.put("wind_directions", wind==null?"":wind.select("span[title]").eachAttr("title").stream()
                    .filter(value -> !value.isBlank()).distinct().reduce((left, right) -> left + "/" + right).orElse(""));
            samples.add(new Sample(date.atStartOfDay(ZONE).toInstant(),
                    Collections.unmodifiableMap(values), Map.copyOf(text)));
        }
        // zs_7d 更新时间只作为页面日期锚点；可能晚于抓取时刻，不能冒充预报发布时间。
        return new FetchResult(Source.CHINA_WEATHER, Product.FORECAST_DAILY, fetchedAt, null,
                null, null, List.copyOf(samples), VERSION);
    }

    private static void validateDay(Element item, LocalDate expected) {
        Element header = item.selectFirst("h1");
        if (header == null) {
            throw dateAmbiguous(WeatherSourceException.Detail.DAY_HEADER_MISSING);
        }
        Matcher complete = DATE.matcher(header.text());
        if (complete.find()) {
            try {
                LocalDate displayed = LocalDate.of(expected.getYear(), Integer.parseInt(complete.group(1)),
                        Integer.parseInt(complete.group(2)));
                if (!displayed.equals(expected)) {
                    throw dateAmbiguous(WeatherSourceException.Detail.DAY_HEADER_MISMATCH);
                }
                return;
            } catch (java.time.DateTimeException exception) {
                throw dateAmbiguous(WeatherSourceException.Detail.DAY_HEADER_INVALID);
            }
        }
        Matcher day = DAY.matcher(header.text());
        // 页面列表常只显示日号；顺序和完整更新日共同确定月份与年份，避免按抓取日猜测。
        if (!day.matches() || Integer.parseInt(day.group(1)) != expected.getDayOfMonth()) {
            throw dateAmbiguous(WeatherSourceException.Detail.DAY_HEADER_MISMATCH);
        }
    }

    private static Double parseTemperature(Element element) {
        if (element == null) {
            return null;
        }
        Matcher matcher = TEMP.matcher(element.text());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.valueOf(matcher.group());
        } catch (NumberFormatException exception) {
            throw structureError();
        }
    }

    private static WeatherSourceException dateAmbiguous(WeatherSourceException.Detail detail) {
        return new WeatherSourceException(WeatherSourceException.Code.DATE_AMBIGUOUS, detail);
    }

    private static WeatherSourceException structureError() {
        return new WeatherSourceException(WeatherSourceException.Code.STRUCTURE_ERROR);
    }
}
