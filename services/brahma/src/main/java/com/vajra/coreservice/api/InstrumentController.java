package com.vajra.coreservice.api;

import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InstrumentController {

    private final InstrumentService instruments;

    InstrumentController(InstrumentService instruments) {
        this.instruments = instruments;
    }

    @GetMapping("/api/v1/instruments")
    List<Instrument> list() {
        return instruments.all();
    }
}
