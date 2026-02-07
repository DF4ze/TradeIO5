package fr.ses10doigts.tradeIO5.service.tree.scenario.factory;

public sealed interface ScenarioOwner
        permits ScenarioOwner.SystemOwner, ScenarioOwner.UserOwner {

    String getId();

    static ScenarioOwner fromString( String userId ){
        if("SYSTEM".equals(userId)){
            return SYSTEM;

        }else if( userId != null ){
            return user( userId );

        }

        return null;
    }

    record SystemOwner() implements ScenarioOwner {
        @Override
        public String getId() {
            return "SYSTEM";
        }
    }
    record UserOwner(String userId) implements ScenarioOwner {
        @Override
        public String getId() {
            return userId;
        }
    }

    ScenarioOwner SYSTEM = new SystemOwner();
    static ScenarioOwner user(String userId) {
        return new UserOwner(userId);
    }
}