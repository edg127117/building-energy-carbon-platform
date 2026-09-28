package com.platform.weather.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.weather.source.WeatherSourceModels.FetchResult;
import com.platform.weather.source.WeatherSourceModels.Product;
import com.platform.weather.source.WeatherSourceModels.Request;
import com.platform.weather.source.WeatherSourceModels.Sample;
import com.platform.weather.source.WeatherSourceModels.Source;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 校验 Open-Meteo 响应的时区与字段结构，保留缺失值并将来源时间转换为统一时间戳。 */
final class OpenMeteoParser {
    static final String VERSION = "open-meteo-v2";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();

    FetchResult parse(String body, Request request, Instant fetchedAt) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!"Asia/Shanghai".equals(root.path("timezone").asText())) {
                throw structureError();
            }
            boolean daily = request.product() == Product.FORECAST_DAILY || request.product() == Product.HISTORY_DAILY;
            boolean current = request.product() == Product.CURRENT;
            String sectionName = current ? "current" : daily ? "daily" : "hourly";
            JsonNode section = root.path(sectionName);
            if (!section.isObject()) {
                throw structureError();
            }
            List<String> fields = fields(request.product());
            validateUnits(root.path(sectionName + "_units"), fields);
            JsonNode times = current ? section.path("time") : section.path("time");
            List<Sample> samples = new ArrayList<>();
            if (current) {
                Map<String, Double> values = readValues(section, fields);
                samples.add(sample(parseTime(times.asText(), false),values));
            } else {
                if (!times.isArray() || times.isEmpty()) {
                    throw structureError();
                }
                int size = times.size();
                for (String field : fields) {
                    JsonNode array = section.path(field);
                    if (!array.isArray() || array.size() != size) {
                        throw structureError();
                    }
                }
                for (int index = 0; index < size; index++) {
                    Map<String, Double> values = new LinkedHashMap<>();
                    for (String field : fields) {
                        JsonNode value = section.path(field).get(index);
                        values.put(field, value == null || value.isNull() ? null : numeric(value));
                    }
                    samples.add(sample(parseTime(times.get(index).asText(), daily),values));
                }
            }
            Double gridLatitude = optionalNumber(root.path("latitude"));
            Double gridLongitude = optionalNumber(root.path("longitude"));
            return new FetchResult(Source.OPEN_METEO, request.product(), fetchedAt, null,
                    gridLatitude, gridLongitude, List.copyOf(samples), VERSION);
        } catch (WeatherSourceException exception) {
            throw exception;
        } catch (IOException | DateTimeParseException | ArithmeticException exception) {
            throw new WeatherSourceException(WeatherSourceException.Code.STRUCTURE_ERROR);
        }
    }

    /** 保留源编码；展示映射和期间语义由后端统一，不能把小时累计量误读为瞬时值。 */
    private static Sample sample(Instant at,Map<String,Double> values) {
        Map<String,String> text=new LinkedHashMap<>();
        Double code=values.get("weather_code");
        if(code!=null) {
            text.put("weather",weatherText(code));
            text.put("weather_mapping_version","open-meteo-wmo-2026-09");
        }
        for(String field:List.of("precipitation","shortwave_radiation"))if(values.containsKey(field)) {
            text.put(field+"_period_start",at.minusSeconds(3600).toString());
            text.put(field+"_period_end",at.toString());
            text.put(field+"_aggregation",field.equals("precipitation")?"SUM":"MEAN");
        }
        return new Sample(at,values,text);
    }
    // 来源映射依据：https://open-meteo.com/en/docs#weathervariables （WMO 代码表）。
    static String weatherText(Double code) {
        if(code==null||code!=Math.rint(code))return "未知天气代码";
        return switch(code.intValue()) {
            case 0 -> "晴";case 1 -> "基本晴朗";case 2 -> "局部多云";case 3 -> "阴";
            case 45 -> "雾";case 48 -> "雾凇";
            case 51 -> "轻微毛毛雨";case 53 -> "中等毛毛雨";case 55 -> "浓密毛毛雨";
            case 56 -> "轻微冻毛毛雨";case 57 -> "浓密冻毛毛雨";
            case 61 -> "小雨";case 63 -> "中雨";case 65 -> "大雨";case 66 -> "轻微冻雨";case 67 -> "强冻雨";
            case 71 -> "小雪";case 73 -> "中雪";case 75 -> "大雪";case 77 -> "米雪";
            case 80 -> "小阵雨";case 81 -> "中等阵雨";case 82 -> "强阵雨";
            case 85 -> "小阵雪";case 86 -> "强阵雪";
            case 95 -> "雷暴";case 96 -> "雷暴伴轻微冰雹";case 97 -> "强雷暴";case 99 -> "雷暴伴强冰雹";
            default -> "未知天气代码";
        };
    }

    private static List<String> fields(Product product) {
        return switch (product) {
            case CURRENT -> List.of("temperature_2m", "relative_humidity_2m", "weather_code",
                    "wind_speed_10m", "wind_direction_10m");
            case FORECAST_HOURLY, HISTORY_HOURLY -> List.of("temperature_2m", "relative_humidity_2m",
                    "precipitation", "weather_code", "wind_speed_10m", "wind_direction_10m",
                    "shortwave_radiation");
            case FORECAST_DAILY -> List.of("temperature_2m_max", "temperature_2m_min", "weather_code",
                    "wind_speed_10m_max", "wind_direction_10m_dominant");
            case HISTORY_DAILY -> List.of("temperature_2m_max", "temperature_2m_min", "weather_code");
        };
    }

    private static Map<String, Double> readValues(JsonNode section, List<String> fields) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode value = section.get(field);
            if (value == null) {
                throw structureError();
            }
            values.put(field, value.isNull() ? null : numeric(value));
        }
        return values;
    }

    private static void validateUnits(JsonNode units, List<String> fields) {
        if (!units.isObject()) {
            throw structureError();
        }
        for (String field : fields) {
            String expected = switch (field) {
                case "temperature_2m", "temperature_2m_max", "temperature_2m_min" -> "°C";
                case "relative_humidity_2m" -> "%";
                case "precipitation" -> "mm";
                case "weather_code" -> "wmo code";
                case "wind_speed_10m", "wind_speed_10m_max" -> "m/s";
                case "wind_direction_10m", "wind_direction_10m_dominant" -> "°";
                case "shortwave_radiation" -> "W/m²";
                default -> throw structureError();
            };
            if (!expected.equals(units.path(field).asText())) {
                throw structureError();
            }
        }
    }

    private static Double numeric(JsonNode node) {
        if (!node.isNumber()) {
            throw structureError();
        }
        double value = node.asDouble();
        if (!Double.isFinite(value)) {
            throw structureError();
        }
        return value;
    }

    private static Double optionalNumber(JsonNode node) {
        return node.isNumber() ? numeric(node) : null;
    }

    private static Instant parseTime(String value, boolean dateOnly) {
        return (dateOnly ? LocalDate.parse(value).atStartOfDay() : LocalDateTime.parse(value))
                .atZone(ZONE).toInstant();
    }

    private static WeatherSourceException structureError() {
        return new WeatherSourceException(WeatherSourceException.Code.STRUCTURE_ERROR);
    }
}
