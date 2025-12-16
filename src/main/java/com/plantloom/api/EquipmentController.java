package com.plantloom.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantloom.api.ApiDtos.EquipmentView;
import com.plantloom.api.ApiDtos.MaintenanceRequest;
import com.plantloom.equipment.Equipment;
import com.plantloom.equipment.EquipmentPool;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentPool pool;
    private final EventBus bus;

    public EquipmentController(EquipmentPool pool, EventBus bus) {
        this.pool = pool;
        this.bus = bus;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return Map.of(
                "types", pool.stats(),
                "units", pool.units().stream().map(EquipmentView::of).toList());
    }

    @GetMapping("/{unitId}")
    public EquipmentView unit(@PathVariable String unitId) {
        return EquipmentView.of(pool.unit(unitId));
    }

    @GetMapping("/stats")
    public List<EquipmentPool.TypeStats> stats() {
        return pool.stats();
    }

    /** Takes a unit out of (or back into) service. Busy units finish their current job first. */
    @PostMapping("/{unitId}/maintenance")
    public EquipmentView maintenance(@PathVariable String unitId, @RequestBody MaintenanceRequest request) {
        Equipment unit = pool.setMaintenance(unitId, request.enabled());
        bus.publish(WorkflowEvent.of(EventType.EQUIPMENT_MAINTENANCE)
                .attr("unitId", unitId)
                .attr("enabled", request.enabled())
                .attr("state", unit.getState().name()));
        return EquipmentView.of(unit);
    }
}
