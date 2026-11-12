-- Réinjection des éléments LOCAUX après restauration de la copie prod (importée par import-dev.sh / import-dev.ps1).
-- Prérequis : tables temporaires _local_keys, _local_providers, _local_kill remplies par le script appelant.
-- Provider présent en local mais pas dans la copie prod : recréé avant les clés (sinon ses clés seraient perdues).
INSERT INTO provider (enabled, created_at, name, api_base_url, code)
  SELECT lp.enabled, NOW(6), lp.name, lp.api_base_url, lp.code FROM _local_providers lp
   WHERE NOT EXISTS (SELECT 1 FROM provider p WHERE p.code = lp.code);
-- Kill switch : l'état local est conservé tel quel (s'il n'y a pas d'état local, la copie reste engagée).
UPDATE execution_control ec JOIN _local_kill k SET ec.kill_switch = k.kill_switch, ec.updated_by = 'dev-sync';
UPDATE api_credentials ac JOIN users u ON u.id=ac.user_id JOIN provider p ON p.id=ac.web_provider_id
  JOIN _local_keys k ON k.username=u.username AND k.code=p.code AND k.scope=ac.scope
   SET ac.api_key=k.api_key, ac.secret_key=k.secret_key, ac.passphrase=k.passphrase, ac.enabled=k.enabled;
INSERT INTO api_credentials (enabled, created_at, user_id, web_provider_id, api_key, passphrase, secret_key, scope)
  SELECT k.enabled, NOW(6), u.id, p.id, k.api_key, k.passphrase, k.secret_key, k.scope
    FROM _local_keys k JOIN users u ON u.username=k.username JOIN provider p ON p.code=k.code
   WHERE NOT EXISTS (SELECT 1 FROM api_credentials ac WHERE ac.user_id=u.id AND ac.web_provider_id=p.id AND ac.scope=k.scope);

