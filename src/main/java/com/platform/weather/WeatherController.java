package com.platform.weather;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import com.platform.weather.source.WeatherSourceModels.Product;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import static com.platform.weather.WeatherModels.*;

/** 建筑范围由应用服务校验；读取端点无采集、计算副作用。 */
@RestController
@RequestMapping("/v1/weather")
public class WeatherController {
    private final WeatherService service;
    private final WeatherQueryService query;
    public WeatherController(WeatherService service,WeatherQueryService query) { this.service=service;this.query=query; }
    @GetMapping("/locations")
    public Result<com.platform.framework.web.PageResponse<Binding>> locations(Authentication a,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(service.locations(SecurityUser.userId(a),SecurityUser.roles(a),page,size));
    }
    @GetMapping("/buildings/{building}/overview")
    public Result<Overview> overview(Authentication a,@PathVariable String building) { return Result.success(query.overview(SecurityUser.userId(a),SecurityUser.roles(a),building)); }
    @GetMapping("/buildings/{building}/hourly")
    public Result<java.util.List<WeatherSeries>> hourly(Authentication a,@PathVariable String building,@RequestParam Product product,@RequestParam Instant from,@RequestParam Instant to) {
        return Result.success(query.hourly(SecurityUser.userId(a),SecurityUser.roles(a),building,product,from,to));
    }
    @GetMapping("/buildings/{building}/daily")
    public Result<java.util.List<WeatherQueryService.DailyWeather>> daily(Authentication a,@PathVariable String building,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate) {
        return Result.success(query.daily(SecurityUser.userId(a),SecurityUser.roles(a),building,startDate,endDate));
    }
    @GetMapping("/buildings/{building}/energy-comparison/daily")
    public Result<Comparison> comparison(Authentication a,@PathVariable String building,@RequestParam String systemId,@RequestParam String pointId,
            @RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(defaultValue="temperature") String metric) {
        return Result.success(query.comparison(SecurityUser.userId(a),SecurityUser.roles(a),building,systemId,pointId,startDate,endDate,metric));
    }
    @PostMapping("/buildings/{building}/binding-versions")
    public Result<Binding> binding(Authentication a,@PathVariable String building,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BindingRequest request) {
        return Result.success(service.saveBinding(SecurityUser.userId(a),SecurityUser.roles(a),building,key,request));
    }
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<java.util.List<JobView>> submit(Authentication a,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FetchRequest request) {
        return Result.success(service.submit(SecurityUser.userId(a),SecurityUser.roles(a),key,request));
    }
    @PostMapping("/buildings/{building}/energy-comparison/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<java.util.List<JobView>> energy(Authentication a,@PathVariable String building,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody EnergyRequest request) {
        return Result.success(service.submitEnergy(SecurityUser.userId(a),SecurityUser.roles(a),building,key,request));
    }
    @GetMapping("/jobs/{id}")
    public Result<JobView> job(Authentication a,@PathVariable String id) { return Result.success(service.job(SecurityUser.userId(a),SecurityUser.roles(a),id)); }
    @GetMapping("/jobs")
    public Result<com.platform.framework.web.PageResponse<JobView>> jobs(Authentication a,@RequestParam String buildingId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(service.jobs(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,page,size));
    }
}
