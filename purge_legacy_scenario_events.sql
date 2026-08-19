-- Purge des ScenarioEvent "legacy" (antérieurs au champ scenarioId, cf. DecisionScenarioRestoreService)
-- Cible : exactement les 14 lignes dont l'id a été loggé en WARN au démarrage du 2026-08-17
-- (dates ULID décodées : 2026-07-05 -> 2026-08-06, toutes avant le refacto DTO du 04/07 et
-- Palier 3 étape 4 du 13/08 qui a introduit scenarioId). Sans danger : ce sont des lignes mortes,
-- déjà explicitement ignorées par la restauration, jamais lues ailleurs dans l'application.

-- 1) Vérification avant suppression : doit renvoyer exactement 14.
SELECT COUNT(*) AS matching_rows
FROM events
WHERE type = 'SCENARIO'
  AND id IN (
    '[OpinionEvent-01KWQVDGD4CC4YRGYYEASDP5DY][DefaultMarketOpinion]01KWQVDGD3P518V8E3CH4T5FBQ',
    '[OpinionEvent-01KWSCB4R9GV0AV3QS0KWC106V][DefaultMarketOpinion]01KWSCB4R8NB1Z5D0ZEWGBWW6C',
    '[OpinionEvent-01KWSCB5FFY2D0TT39TEC41PG6][GlobalMarketOpinion]01KWSCB5FEPQVNTRQ7NZEK9EDM',
    '[OpinionEvent-01KWSCDYS2SEWT74052SFTZ6DD][ExternalMarketOpinion]01KWSCDYS2RKGRZ44TGFHP6TAW',
    '[OpinionEvent-01KWSCQSH1J5EBEV54KS09WRCE][GlobalMarketOpinion]01KWSCQSH0ZFY3Z1JKE2K5HVYB',
    '[OpinionEvent-01KWSCTSS2QERWSR2DBW64CR9M][GlobalMarketOpinion]01KWSCTSS12YH30B7S0QVPKC88',
    '[OpinionEvent-01KXHEEVV36V4NKZ4BSJCCKNCD][GlobalMarketOpinion]01KXHEEVV2BMQGXDWT7ZBQRJCX',
    '[OpinionEvent-01KXHFX19W6XBCSK8PFHXDBZ4H][MacroMarketOpinion]01KXHFX19VMA8QYFYZ5M7XQ6TQ',
    '[OpinionEvent-01KXHFXD0H1Y6FMQH6CTBWKT7N][GlobalMarketOpinion]01KXHFXD0HKGTTMFFT039ETB8H',
    '[OpinionEvent-01KYM16MZ7PJANHKXS9RMN3KRE][GlobalMarketOpinion]01KYM16MZ6167N3MCG2PQ61H4R',
    '[OpinionEvent-01KYM16P3664TRZD8G3W72PT7F][MacroMarketOpinion]01KYM16P363F5NTWDK7F1M65TV',
    '[OpinionEvent-01KZAWYM5FA7VATMZ3061F3REW][DefaultMarketOpinion]01KZAWYM5DG2GANM7H04TBDPAG',
    '[OpinionEvent-01KZB1AV79R9DC3205R0DHR6SS][MacroMarketOpinion]01KZB1AV78YQFA0DG30S89EBHH',
    '[OpinionEvent-01KZB1AWT4BW3SMP68SQZMFVP5][ExternalMarketOpinion]01KZB1AWT40V3P05ZFZ9D7R9XF'
  );

-- 2) Suppression effective (dans une transaction : ROLLBACK possible si le COUNT ci-dessus
--    n'affichait pas 14, ou si tu veux annuler après coup et avant COMMIT).
START TRANSACTION;

DELETE FROM events
WHERE type = 'SCENARIO'
  AND id IN (
    '[OpinionEvent-01KWQVDGD4CC4YRGYYEASDP5DY][DefaultMarketOpinion]01KWQVDGD3P518V8E3CH4T5FBQ',
    '[OpinionEvent-01KWSCB4R9GV0AV3QS0KWC106V][DefaultMarketOpinion]01KWSCB4R8NB1Z5D0ZEWGBWW6C',
    '[OpinionEvent-01KWSCB5FFY2D0TT39TEC41PG6][GlobalMarketOpinion]01KWSCB5FEPQVNTRQ7NZEK9EDM',
    '[OpinionEvent-01KWSCDYS2SEWT74052SFTZ6DD][ExternalMarketOpinion]01KWSCDYS2RKGRZ44TGFHP6TAW',
    '[OpinionEvent-01KWSCQSH1J5EBEV54KS09WRCE][GlobalMarketOpinion]01KWSCQSH0ZFY3Z1JKE2K5HVYB',
    '[OpinionEvent-01KWSCTSS2QERWSR2DBW64CR9M][GlobalMarketOpinion]01KWSCTSS12YH30B7S0QVPKC88',
    '[OpinionEvent-01KXHEEVV36V4NKZ4BSJCCKNCD][GlobalMarketOpinion]01KXHEEVV2BMQGXDWT7ZBQRJCX',
    '[OpinionEvent-01KXHFX19W6XBCSK8PFHXDBZ4H][MacroMarketOpinion]01KXHFX19VMA8QYFYZ5M7XQ6TQ',
    '[OpinionEvent-01KXHFXD0H1Y6FMQH6CTBWKT7N][GlobalMarketOpinion]01KXHFXD0HKGTTMFFT039ETB8H',
    '[OpinionEvent-01KYM16MZ7PJANHKXS9RMN3KRE][GlobalMarketOpinion]01KYM16MZ6167N3MCG2PQ61H4R',
    '[OpinionEvent-01KYM16P3664TRZD8G3W72PT7F][MacroMarketOpinion]01KYM16P363F5NTWDK7F1M65TV',
    '[OpinionEvent-01KZAWYM5FA7VATMZ3061F3REW][DefaultMarketOpinion]01KZAWYM5DG2GANM7H04TBDPAG',
    '[OpinionEvent-01KZB1AV79R9DC3205R0DHR6SS][MacroMarketOpinion]01KZB1AV78YQFA0DG30S89EBHH',
    '[OpinionEvent-01KZB1AWT4BW3SMP68SQZMFVP5][ExternalMarketOpinion]01KZB1AWT40V3P05ZFZ9D7R9XF'
  );

-- Si ROW_COUNT() ci-dessous affiche 14 : COMMIT;
-- Sinon : ROLLBACK; et regarde ce qui a matché avant de recommencer.
SELECT ROW_COUNT() AS deleted_rows;

COMMIT;
