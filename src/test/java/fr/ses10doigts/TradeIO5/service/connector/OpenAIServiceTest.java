package fr.ses10doigts.tradeIO5.service.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import fr.ses10doigts.tradeIO5.model.dto.MyResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OpenAIServiceTest {

    @Autowired
    OpenAIService service;

    @Test
    void basicTest() throws JsonProcessingException {
        MyResponseDTO dto = service.askForSomething1("Put 'test' in 'indic' and '1' in 'value'");

        assertNotNull(dto);
        assertEquals("test", dto.indic());
        assertEquals(1, dto.value());
    }

}