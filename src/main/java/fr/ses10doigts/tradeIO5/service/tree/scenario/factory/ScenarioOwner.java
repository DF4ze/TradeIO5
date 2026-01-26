package fr.ses10doigts.tradeIO5.service.tree.scenario.factory;

public sealed interface ScenarioOwner
        permits ScenarioOwner.SystemOwner, ScenarioOwner.UserOwner {

    record SystemOwner() implements ScenarioOwner {}
    record UserOwner(String userId) implements ScenarioOwner {}

    static ScenarioOwner SYSTEM = new SystemOwner();
    static ScenarioOwner user(String userId) {
        return new UserOwner(userId);
    }
}