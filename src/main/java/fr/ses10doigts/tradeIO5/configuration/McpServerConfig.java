package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.service.dca.DcaMcpTools;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enregistre les tools MCP auprès du serveur (spring-ai-starter-mcp-server-webmvc) : chaque
 * méthode annotée {@code @Tool} devient un tool appelable.
 * <ul>
 *     <li>{@link TreeAnalysisMcpTools} : get_indicator, evaluate_strategy, get_opinion</li>
 *     <li>{@link DcaMcpTools} : calculate_dca (cf. docs/etude-dca-tool-mcp.md)</li>
 * </ul>
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider treeAnalysisToolCallbackProvider(TreeAnalysisMcpTools treeAnalysisMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(treeAnalysisMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider dcaToolCallbackProvider(DcaMcpTools dcaMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(dcaMcpTools)
                .build();
    }
}
