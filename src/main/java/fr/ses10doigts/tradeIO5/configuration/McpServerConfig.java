package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enregistre {@link TreeAnalysisMcpTools} auprès du serveur MCP (spring-ai-starter-mcp-server-webmvc) :
 * chaque méthode annotée {@code @Tool} devient un tool MCP appelable (get_indicator,
 * evaluate_strategy, get_opinion).
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider treeAnalysisToolCallbackProvider(TreeAnalysisMcpTools treeAnalysisMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(treeAnalysisMcpTools)
                .build();
    }
}
