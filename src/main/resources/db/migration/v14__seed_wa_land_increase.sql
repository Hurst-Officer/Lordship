-- ============================================================
-- V14: Seed - WA Land Rent Increase Notice (MHLTA / WA 1217)
--
-- This is the Washington Manufactured/Mobile Home Landlord-Tenant
-- Act rent and fee increase notice for LAND agreements.
--
-- IMPORTANT:
-- The current DocumentToken vocabulary does not yet contain tokens
-- for:
--   - increase effective date
--   - increase percentage
--   - dollar increase per month
--
-- Do NOT invent those token names here. They must be added to
-- DocumentToken + its resolver first.
--
-- Existing tokens used here:
--   property.community_name
--   property.city
--   property.state
--   property.zip
--   landlord.name
--   lot.address
--   lot.lot_number
--   tenancy.tenant_names
--   term.rate
--   instrument.generated_on
--   instrument.execution_year
-- ============================================================


INSERT INTO document_template
(uuid, name, agreement_type, instrument_type, version, created_by)
VALUES
    (
        '0199a000-0000-7000-8000-000000000008',
        'WA Manufactured Home Lot Rent Increase Notice 2026',
        'LAND',
        'INCREASE_NOTICE',
        1,
        '00000000-0000-7000-8000-000000000002'
    );


-- ── Styles ──────────────────────────────────────────────────

INSERT INTO document_style
(uuid, template, name, css, target, note, created_by)
VALUES
    (
        '0199a003-0000-7000-8000-000000000004',
        '0199a000-0000-7000-8000-000000000008',
        'statutory-notice',
        'font-size: 11pt; border: 2px solid #000; padding: 3mm; margin-top: 4mm;',
        NULL,
        'Statutory MHLTA notice language.',
        '00000000-0000-7000-8000-000000000002'
    ),
    (
        '0199a003-0000-7000-8000-000000000005',
        '0199a000-0000-7000-8000-000000000008',
        'community-name',
        'text-align: center; font-weight: bold; font-size: 13pt; text-decoration: underline;',
        NULL,
        NULL,
        '00000000-0000-7000-8000-000000000002'
    ),
    (
        '0199a003-0000-7000-8000-000000000006',
        '0199a000-0000-7000-8000-000000000008',
        'form-lines',
        'line-height: 1.85;',
        NULL,
        'Fill-in form fields retained where the current token vocabulary cannot supply the value.',
        '00000000-0000-7000-8000-000000000002'
    );


-- ── Section ─────────────────────────────────────────────────

INSERT INTO document_section
(uuid, template, ordinal, name, section_key, signature_block,
 listed_as_addendum, required, statute_ref, created_by)
VALUES
    (
        '0199a001-0000-7000-8000-000000000008',
        '0199a000-0000-7000-8000-000000000008',
        1000,
        'Rent and Fee Increase Notice to Tenants',
        'INCREASE_NOTICE',
        TRUE,
        FALSE,
        TRUE,
        'Chapter 209, Laws of 2025, §§ 201-202',
        '00000000-0000-7000-8000-000000000002'
    );


-- ── Clauses ─────────────────────────────────────────────────

INSERT INTO template_clause
(
    uuid,
    section,
    ordinal,
    clause_key,
    title,
    body,
    parent,
    variant_of,
    numbered,
    requires_next,
    style,
    condition_field,
    condition_values,
    required,
    statute_ref,
    note,
    created_by
)
VALUES

-- Header
(
    '0199a002-0000-7000-8000-000000000195',
    '0199a001-0000-7000-8000-000000000008',
    10,
    'INCREASE_NOTICE_HEADER',
    NULL,
    E'{{property.community_name}}\\n\\n'
        E'<b>RENT AND FEE INCREASE NOTICE TO TENANTS (MHLTA)</b>\\n\\n'
        E'<b>Tenant Name:</b> {{tenancy.tenant_names}}\\n'
        E'<b>Leasehold Address:</b> {{lot.address}}, Lot {{lot.lot_number}}, '
        E'{{property.city}}, {{property.state}} {{property.zip}}',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-7000-8000-000000000005',
    NULL,
    NULL,
    TRUE,
    NULL,
    NULL,
    '00000000-0000-7000-8000-000000000002'
),

-- Statutory introduction
(
    '0199a002-0000-7000-8000-000000000196',
    '0199a001-0000-7000-8000-000000000008',
    20,
    'INCREASE_NOTICE_STATUTE',
    NULL,
    E'This notice is required by Washington state law to inform you of your rights '
        E'regarding rent and fee increases. Your rent or rental amount includes all '
        E'recurring and periodic charges, sometimes referred to as rent and fees, '
        E'identified in your rental agreement for the use and occupancy of your '
        E'manufactured/mobile home lot. Washington state limits how much your landlord '
        E'can raise your rent and any other recurring or periodic charges for the use '
        E'and occupancy of your manufactured/mobile home lot.\\n\\n'
        E'(1) Your landlord can raise your rent and other recurring or periodic charges '
        E'once every 12 months by up to five percent, as allowed by section 201 '
        E'(Chapter 209, Laws of 2025 (WA)) of this act. Your landlord is not required '
        E'to raise the rent or other recurring or periodic charges by any amount.\\n\\n'
        E'(2) Your landlord may be exempt from the five percent limit on increases for '
        E'rent and other recurring or periodic charges for the reasons described in '
        E'section 202 (Chapter 209, Laws of 2025 (WA)) of this act. If your landlord '
        E'claims an exemption, your landlord is required to include supporting facts '
        E'with this notice.\\n\\n'
        E'(3) Your landlord must properly and fully complete the form below to notify '
        E'you of any increases in rent and other recurring or periodic charges and '
        E'any exemptions claimed.',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000004',
    NULL,
    NULL,
    TRUE,
    'Chapter 209, Laws of 2025, §§ 201-202',
    NULL,
    '00000000-0000-0000-0000-000000000002'
),

-- Increase details
(
    '0199a002-0000-7000-8000-000000000197',
    '0199a001-0000-7000-8000-000000000008',
    30,
    'INCREASE_NOTICE_DETAILS',
    NULL,
    E'Your landlord, <b>{{landlord.name}}</b>, intends to (check one of the following):\\n\\n'
        E'<b>X</b> Raise your rent and/or other recurring and periodic charges:\\n'
        E'Your total increase in rent and other recurring or periodic charges effective '
        E'(<rule w="12">) will be (<rule w="10"> %), which totals an additional '
        E'(<rule w="14">) per month, for a new total amount of '
        E'(<b>{{term.rate}}</b>) per month for rent and other recurring or periodic charges.\\n\\n'
        E'<i>The effective date, increase percentage, and dollar increase remain fill-in '
        E'fields until corresponding DocumentToken values are added.</i>',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000006',
    NULL,
    NULL,
    TRUE,
    'Chapter 209, Laws of 2025, § 201',
    'Do not replace the fill-in fields with invented token names. Add dedicated increase-notice tokens first.',
    '00000000-0000-0000-0000-000000000002'
),

-- Authorization / maximum selection
(
    '0199a002-0000-7000-8000-000000000198',
    '0199a001-0000-7000-8000-000000000008',
    40,
    'INCREASE_NOTICE_AUTHORIZATION',
    NULL,
    E'This increase in rent and/or other recurring and periodic charges is allowed '
        E'by state law and is (check one of the following):\\n\\n'
        E'<b>__</b> A lower increase than the maximum allowed by state law.\\n'
        E'<b>__</b> The maximum increase allowed by state law.\\n'
        E'<b>__</b> Authorized by an exemption under section 202 '
        E'(Chapter 209, Laws of 2025 (WA)) of this act. If the increase is authorized '
        E'by an exemption, your landlord must fill out the section of the form below.',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000006',
    NULL,
    NULL,
    TRUE,
    'Chapter 209, Laws of 2025, §§ 201-202',
    NULL,
    '00000000-0000-0000-0000-000000000002'
),

-- Exemptions
(
    '0199a002-0000-7000-8000-000000000199',
    '0199a001-0000-7000-8000-000000000008',
    50,
    'INCREASE_NOTICE_EXEMPTIONS',
    'EXEMPTIONS CLAIMED BY LANDLORD',
    E'<b>EXEMPTIONS CLAIMED BY LANDLORD</b>\\n\\n'
        E'I, <rule w="30">, certify that I am allowed under Washington state law to '
        E'raise your rent and other recurring or periodic charges by '
        E'(<rule w="10"> %), which is more than the maximum increase otherwise allowed '
        E'by state law, because I am claiming the following exemption under section '
        E'202 (Chapter 209, Laws of 2025 (WA)) of this act (check one of the following):\\n\\n'
        E'__ You live on a manufactured/mobile home lot owned by a public housing '
        E'authority, public development authority, or nonprofit organization where '
        E'maximum rents are regulated by other laws or local, state, or federal '
        E'affordable housing program requirements, or a qualified low-income housing '
        E'development as defined in RCW 82.45.010, where the property is owned by a '
        E'public housing authority, public development authority, or nonprofit '
        E'organization. (The landlord must include facts or attach documents supporting '
        E'the exemption.)\\n\\n'
        E'__ You live in a manufactured/mobile home community that was purchased during '
        E'the past 12 months by an eligible organization as defined in RCW 59.20.030 '
        E'whose mission aligns with the long-term preservation and affordability of '
        E'your manufactured/mobile home community, so the eligible organization may '
        E'increase the rent and other recurring or periodic charges for your '
        E'manufactured/mobile home community in an amount greater than allowed under '
        E'section 201 (Chapter 209, Laws of 2025 (WA)) of this act as needed to cover '
        E'the cost of purchasing your manufactured/mobile home community if the '
        E'increase is approved by vote or agreement with the majority of the '
        E'manufactured/mobile home owners in your manufactured/mobile home community. '
        E'(The landlord must include facts or attach documents supporting the exemption.)\\n\\n'
        E'__ Your manufactured/mobile home lot rental agreement is up for first renewal '
        E'after it was transferred to you under RCW 59.20.073, so your landlord is '
        E'allowed to make a one-time increase to your rent and other recurring or '
        E'periodic charges in an amount not limited by section 201 '
        E'(Chapter 209, Laws of 2025 (WA)) of this act. In order to exercise this '
        E'one-time increase option, the landlord must have provided you with notice '
        E'of this option prior to the final transfer of the rental agreement to you. '
        E'(The landlord must include facts or attach documents supporting the exemption, '
        E'including evidence that proper notice of this one-time increase option was '
        E'provided to you prior to the final transfer of the rental agreement.)',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000006',
    NULL,
    NULL,
    TRUE,
    'Chapter 209, Laws of 2025, § 202',
    NULL,
    '00000000-0000-0000-0000-000000000002'
),

-- Landlord signature
(
    '0199a002-0000-7000-8000-000000000200',
    '0199a001-0000-7000-8000-000000000008',
    60,
    'INCREASE_NOTICE_SIGNATURE',
    NULL,
    E'DATED this {{instrument.generated_on}}.\\n\\n'
        E'_______________________________\\n'
        E'LANDLORD / AUTHORIZED AGENT\\n\\n'
        E'<b>{{landlord.name}}</b>',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000006',
    NULL,
    NULL,
    TRUE,
    NULL,
    'instrument.generated_on is the document-generation date; the actual service date is a separate fill-in because no service-date token currently exists.',
    '00000000-0000-0000-0000-000000000002'
),

-- Proof of service
(
    '0199a002-0000-7000-8000-000000000201',
    '0199a001-0000-7000-8000-000000000008',
    70,
    'INCREASE_NOTICE_PROOF_OF_SERVICE',
    'PROOF OF SERVICE',
    E'<b>PROOF OF SERVICE</b>\\n\\n'
        E'{{tenancy.tenant_names}} & all other occupants\\n'
        E'{{lot.address}}, Lot {{lot.lot_number}}\\n'
        E'{{property.city}}, {{property.state}} {{property.zip}}\\n\\n'
        E'<b>Leasehold Address</b>\\n'
        E'<b>Name of Document Served:</b> RENT AND FEE INCREASE NOTICE TO TENANTS\\n\\n'
        E'I, the undersigned, being at least eighteen (18) years of age, declare under '
        E'penalty of perjury pursuant to the laws of the State of Washington that I '
        E'served a copy of the attached notice, of which this is a true copy, on the '
        E'above-named tenant(s) in the manner indicated below on this ____ day of '
        E'__________________, {{instrument.execution_year}} (date of service).\\n\\n'
        E'____ I personally delivered a true copy of the Notice to '
        E'__________________ the person(s) entitled thereto at his/her above addressed '
        E'leasehold premises (service on tenant named in lease).\\n\\n'
        E'____ I personally delivered a true copy of the Notice to a person of suitable '
        E'age and discretion and mailed a copy of said notice by first class mail '
        E'postage prepaid to the person entitled thereto at his/her above addressed '
        E'leasehold premises. (Served on adult other than tenant named on lease and '
        E'certified mailed.)\\n\\n'
        E'____ I personally posted a true copy of this Notice in a conspicuous place '
        E'of the above addressed residence/business and mailed a copy of said notice '
        E'by first class mail postage prepaid, to the leasehold premises after '
        E'attempting to deliver a copy to a person therein residing because the tenant '
        E'or person of suitable age and discretion could not be found at the leasehold '
        E'premises. (Posted and certified mailed, no one present at leasehold premises.)\\n\\n'
        E'<b>Mailed To:</b> {{lot.address}}, Lot {{lot.lot_number}}, '
        E'{{property.city}}, {{property.state}} {{property.zip}}\\n\\n'
        E'Executed on this ____ day of __________________, {{instrument.execution_year}}, '
        E'at __________________, Washington.\\n\\n'
        E'______________________________________\\n'
        E'Signature\\n\\n'
        E'<rule w="35">\\n'
        E'Agent for {{landlord.name}}',
    NULL,
    NULL,
    FALSE,
    NULL,
    '0199a003-0000-0000-0000-000000000006',
    NULL,
    NULL,
    TRUE,
    NULL,
    'The actual service date and serving agent are not currently represented by dedicated DocumentTokens.',
    '00000000-0000-0000-0000-000000000002'
);