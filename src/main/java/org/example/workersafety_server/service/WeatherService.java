package org.example.workersafety_server.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

import org.example.workersafety_server.dto.AdminUiModels.Weather;
import org.example.workersafety_server.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Added Open-Meteo integration using configured site coordinates and bounded HTTP timeouts. */
/*
 * 네트워크별 연동 확인 기록 (2026-11-18)
 * 기존 네트워크에서는 Open-Meteo API 요청이 타임아웃되어 날씨를 불러오지 못했다.
 * 휴대폰 핫스팟으로 변경한 뒤에는 API 응답을 정상 수신했으며,
 * 실행 중인 서버의 관리자 날씨 화면에도 데이터가 정상 표시되었다.
 * 네트워크 환경에 따른 연결 차이는 확인했으나 정확한 원인은 확인되지 않았다.
 */
@Service
public class WeatherService {
    private static final String PROVIDER = "Open-Meteo";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String siteName;
    private final String latitude;
    private final String longitude;

    public WeatherService(ObjectMapper mapper,
                          @Value("${app.weather.site-name}") String siteName,
                          @Value("${app.weather.latitude}") String latitude,
                          @Value("${app.weather.longitude}") String longitude) {
        this.mapper = mapper;
        this.siteName = siteName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Weather current() {
        double lat = coordinate(latitude, 90);
        double lon = coordinate(longitude, 180);
        URI uri = URI.create("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon + "&current=temperature_2m,relative_humidity_2m,weather_code"
                + "&temperature_unit=celsius&timeformat=unixtime&timezone=GMT");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            var current = mapper.readTree(response.body()).path("current");
            var temperature = current.path("temperature_2m");
            var humidity = current.path("relative_humidity_2m");
            var code = current.path("weather_code");
            var time = current.path("time");
            if (!temperature.isNumber() || !humidity.isIntegralNumber()
                    || !code.isIntegralNumber() || !time.isIntegralNumber()) {
                throw unavailable();
            }
            return new Weather(siteName, temperature.asDouble(), humidity.asInt(),
                    condition(code.asInt()), Instant.ofEpochSecond(time.asLong()), PROVIDER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | JacksonException | DateTimeException e) {
            throw unavailable();
        }
    }

    private static double coordinate(String value, int limit) {
        try {
            double coordinate = Double.parseDouble(value);
            if (Double.isFinite(coordinate) && Math.abs(coordinate) <= limit) return coordinate;
        } catch (NumberFormatException ignored) {
            // Empty and malformed coordinates use the same configuration error.
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WEATHER_SITE_NOT_CONFIGURED",
                "현장 위도와 경도를 설정해 주세요.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "WEATHER_UNAVAILABLE",
                "현재 날씨를 불러오지 못했습니다.");
    }

    private static String condition(int code) {
        return switch (code) {
            case 0 -> "맑음";
            case 1, 2 -> "구름 조금";
            case 3 -> "흐림";
            case 45, 48 -> "안개";
            case 51, 53, 55 -> "이슬비";
            case 56, 57, 66, 67 -> "어는 비";
            case 61, 63, 65 -> "비";
            case 71, 73, 75, 77, 85, 86 -> "눈";
            case 80, 81, 82 -> "소나기";
            case 95, 96, 99 -> "뇌우";
            default -> "상태 정보 없음";
        };
    }
}
