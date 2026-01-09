package fr.ses10doigts.tradeIO5.service.tool;

public class StringTool {

    public static String cleansBinanceLD( String str ){
        return  (str != null && str.startsWith("LD")) ? str.substring(2) : str;
    }
}
