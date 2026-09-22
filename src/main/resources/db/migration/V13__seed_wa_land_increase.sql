-- ============================================================
-- V13: Seed - WA Manufactured Home Lot Rent Increase Notice
-- Based on "WA 1217 Rent Increase MHLTA.docx"
--
-- Important:
--   PostgreSQL E'' strings use \n for a newline.
--   Do NOT use \\n here.
--
-- Tokens used are limited to tokens currently defined by
-- DocumentToken. The effective date, increase percentage,
-- additional monthly amount, and service date remain fill-in
-- fields because no corresponding tokens currently exist.
-- ============================================================

INSERT INTO document_template
(uuid, name, agreement_type, instrument_type, version, created_by)
VALUES
    ('0199a000-0000-7000-8000-000000000008',
     'WA Manufactured Home Lot Rent Increase Notice 2026',
     'LAND',
     'INCREASE_NOTICE',
     1,
     '00000000-0000-7000-8000-000000000002');


-- ── Styles ──
INSERT INTO document_style
(uuid, template, name, css, target, note, created_by)
VALUES
    ('0199a003-0000-7000-8000-000000000004',
     '0199a000-0000-7000-8000-000000000008',
     'statutory-notice',
     'font-weight: bold; font-size: 12.5pt; border: 2px solid #000; padding: 3mm; margin-top: 4mm;',
     NULL,
     'Statutory notice heading: bold, larger text, set off by a box.',
     '00000000-0000-7000-8000-000000000002'),

    ('0199a003-0000-7000-8000-000000000005',
     '0199a000-0000-7000-8000-000000000008',
     'community-name',
     'text-align: center; font-weight: bold; font-size: 13pt; text-decoration: underline;',
     NULL,
     'Community heading.',
     '00000000-0000-7000-8000-000000000002'),

    ('0199a003-0000-7000-8000-000000000006',
     '0199a000-0000-7000-8000-000000000008',
     'form-lines',
     'line-height: 1.85;',
     NULL,
     'Fill-in form spacing for blanks and checkboxes.',
     '00000000-0000-7000-8000-000000000002');


-- ── Section ──
INSERT INTO document_section
(uuid, template, ordinal, name, section_key, signature_block,
 listed_as_addendum, required, statute_ref, created_by)
VALUES
    ('0199a001-0000-7000-8000-000000000008',
     '0199a000-0000-7000-8000-000000000008',
     1000,
     'Rent and Fee Increase Notice',
     'RENT_INCREASE_NOTICE',
     TRUE,
     FALSE,
     TRUE,
     NULL,
     '00000000-0000-7000-8000-000000000002');


-- ── Clauses ──
INSERT INTO template_clause
(uuid, section, ordinal, clause_key, title, body,
 parent, variant_of, numbered, requires_next, style,
 condition_field, condition_values, required, statute_ref, note, created_by)
VALUES

-- 1. Header / tenant and leasehold information
('0199a002-0000-7000-8000-000000000195',
 '0199a001-0000-7000-8000-000000000008',
 10,
 'INCREASE_NOTICE_HEADER',
 NULL,
 E'<b>{{property.community_name}}</b>\n\n'
     'Tenant Name: {{tenancy.tenant_names}}\n'
     'Leasehold Address: {{lot.address}}, Lot {{lot.lot_number}}, '
     '{{property.city}}, {{property.state}} {{property.zip}}\n\n'
     '<b>RENT AND FEE INCREASE NOTICE TO TENANTS (MHLTA)</b>\n\n',
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
 '00000000-0000-7000-8000-000000000002'),


-- 2. Statutory information
('0199a002-0000-7000-8000-000000000196',
 '0199a001-0000-7000-8000-000000000008',
 20,
 'INCREASE_NOTICE_STATUTE',
 NULL,
 E'This notice is required by Washington state law to inform you of your rights regarding rent and fee increases. '
     'Your rent or rental amount includes all recurring and periodic charges, sometimes referred to as rent and fees, '
     'identified in your rental agreement for the use and occupancy of your manufactured/mobile home lot. '
     'Washington state limits how much your landlord can raise your rent and any other recurring or periodic charges '
     'for the use and occupancy of your manufactured/mobile home lot.\n\n'
     '(1) Your landlord can raise your rent and other recurring or periodic charges once every 12 months by up to '
     'five percent, as allowed by section 201 (Chapter 209, Laws of 2025 (WA)) of this act. Your landlord is not '
     'required to raise the rent or other recurring or periodic charges by any amount.\n\n'
     '(2) Your landlord may be exempt from the five percent limit on increases for rent and other recurring or '
     'periodic charges for the reasons described in section 202 (Chapter 209, Laws of 2025 (WA)) of this act. '
     'If your landlord claims an exemption, your landlord is required to include supporting facts with this notice.\n\n'
     '(3) Your landlord must properly and fully complete the form below to notify you of any increases in rent and '
     'other recurring or periodic charges and any exemptions claimed.',
 NULL,
 NULL,
 TRUE,
 NULL,
 '0199a003-0000-7000-8000-000000000004',
 NULL,
 NULL,
 TRUE,
 NULL,
 NULL,
 '00000000-0000-7000-8000-000000000002'),


-- 3. Increase details
('0199a002-0000-7000-8000-000000000197',
 '0199a001-0000-7000-8000-000000000008',
 30,
 'INCREASE_NOTICE_DETAILS',
 NULL,
 E'Your landlord, {{landlord.name}}, intends to (check one of the following):\n\n'
     '[  ] Raise your rent and/or other recurring and periodic charges: '
     'Your total increase in rent and other recurring or periodic charges effective '
     '<rule w="14"> will be <rule w="10"> %, which totals an additional '
     '<rule w="12"> per month, for a new total amount of '
     '<b>{{term.rate}}</b> per month for rent and other recurring or periodic charges.\n\n'
     '[  ] No increase in rent and/or other recurring and periodic charges.\n\n'
     'This increase in rent and/or other recurring and periodic charges is allowed by state law and is '
     '(check one of the following):\n\n'
     '[  ] A lower increase than the maximum allowed by state law.\n\n'
     '[  ] The maximum increase allowed by state law.\n\n'
     '[  ] Authorized by an exemption under section 202 (Chapter 209, Laws of 2025 (WA)) of this act. '
     'If the increase is authorized by an exemption, your landlord must fill out the section of the form below.',
 NULL,
 NULL,
 TRUE,
 NULL,
 '0199a003-0000-7000-8000-000000000006',
 NULL,
 NULL,
 TRUE,
 NULL,
 'The notice document currently has no tokens for effective date, increase percentage, or additional monthly amount; those remain fill-in fields. term.rate is the new total monthly amount.',
 '00000000-0000-7000-8000-000000000002'),


-- 4. Exemption section
('0199a002-0000-7000-8000-000000000198',
 '0199a001-0000-7000-8000-000000000008',
 40,
 'INCREASE_NOTICE_EXEMPTIONS',
 NULL,
 E'<b>EXEMPTIONS CLAIMED BY LANDLORD</b>\n\n'
     'I, <rule w="25">, certify that I am allowed under Washington state law to raise your rent and '
     'other recurring or periodic charges by <rule w="8"> %, which is more than the maximum increase '
     'otherwise allowed by state law, because I am claiming the following exemption under section 202 '
     '(Chapter 209, Laws of 2025 (WA)) of this act (check one of the following):\n\n'
     '[  ] You live on a manufactured/mobile home lot owned by a public housing authority, public '
     'development authority, or nonprofit organization where maximum rents are regulated by other laws '
     'or local, state, or federal affordable housing program requirements, or a qualified low-income housing '
     'development as defined in RCW 82.45.010, where the property is owned by a public housing authority, '
     'public development authority, or nonprofit organization. (The landlord must include facts or attach '
     'documents supporting the exemption.)\n\n'
     '[  ] You live in a manufactured/mobile home community that was purchased during the past 12 months '
     'by an eligible organization as defined in RCW 59.20.030 whose mission aligns with the long-term '
     'preservation and affordability of your manufactured/mobile home community, so the eligible organization '
     'may increase the rent and other recurring or periodic charges for your manufactured/mobile home community '
     'in an amount greater than allowed under section 201 (Chapter 209, Laws of 2025 (WA)) of this act as '
     'needed to cover the cost of purchasing your manufactured/mobile home community if the increase is approved '
     'by vote or agreement with the majority of the manufactured/mobile home owners in your manufactured/mobile '
     'home community. (The landlord must include facts or attach documents supporting the exemption.)\n\n'
     '[  ] Your manufactured/mobile home lot rental agreement is up for first renewal after it was transferred '
     'to you under RCW 59.20.073, so your landlord is allowed to make a one-time increase to your rent and '
     'other recurring or periodic charges in an amount not limited by section 201 (Chapter 209, Laws of 2025 (WA)) '
     'of this act. In order to exercise this one-time increase option, the landlord must have provided you with '
     'notice of this option prior to the final transfer of the rental agreement to you. (The landlord must '
     'include facts or attach documents supporting the exemption, including evidence that proper notice of this '
     'one-time increase option was provided to you prior to the final transfer of the rental agreement.)',
 NULL,
 NULL,
 TRUE,
 NULL,
 '0199a003-0000-7000-8000-000000000006',
 NULL,
 NULL,
 TRUE,
 NULL,
 NULL,
 '00000000-0000-7000-8000-000000000002'),


-- 5. Landlord signature / dated notice
('0199a002-0000-7000-8000-000000000199',
 '0199a001-0000-7000-8000-000000000008',
 50,
 'INCREASE_NOTICE_SIGNATURE',
 NULL,
 E'DATED this {{instrument.generated_on}}.\n\n'
     '<rule w="40">\n'
     'LANDLORD / AUTHORIZED AGENT\n'
     '<b>{{landlord.name}}</b>',
 NULL,
 NULL,
 FALSE,
 NULL,
 '0199a003-0000-7000-8000-000000000006',
 NULL,
 NULL,
 TRUE,
 NULL,
 'instrument.generated_on is the document generation date. It is used for the dated notice, not the proof-of-service date.',
 '00000000-0000-7000-8000-000000000002'),


-- 6. Proof of service
('0199a002-0000-7000-8000-000000000200',
 '0199a001-0000-7000-8000-000000000008',
 60,
 'INCREASE_NOTICE_PROOF_OF_SERVICE',
 NULL,
 E'<b>PROOF OF SERVICE</b>\n\n'
     '{{tenancy.tenant_names}} & all other occupants\n'
     '{{lot.address}}, Lot {{lot.lot_number}}\n'
     '{{property.city}}, {{property.state}} {{property.zip}}\n\n'
     'Leasehold Address\n\n'
     '<b>RENT AND FEE INCREASE NOTICE TO TENANTS</b>\n'
     'Name of Document Served\n\n'
     'I, the undersigned, being at least eighteen (18) years of age, declare under penalty of perjury '
     'pursuant to the laws of the State of Washington that I served a copy of the attached notice, of which '
     'this is a true copy, on the above-named tenant(s) in the manner indicated below on this <rule w="10"> '
     '{{instrument.execution_year}} (date of service)\n\n'
     '[  ] I personally delivered a true copy of the Notice to <rule w="25"> the person(s) entitled thereto '
     'at his/her above addressed leasehold premises (service on tenant named in lease).\n\n'
     '[  ] I personally delivered a true copy of the Notice to a person of suitable age and discretion and '
     'mailed a copy of said notice by first class mail postage prepaid to the person entitled thereto at his/her '
     'above addressed leasehold premises. (Served on adult other than tenant named on lease and certified mailed.)\n\n'
     '[  ] I personally posted a true copy of this Notice in a conspicuous place of the above addressed '
     'residence/business and mailed a copy of said notice by first class mail postage prepaid, to the leasehold '
     'premises after attempting to deliver a copy to a person therein residing because the tenant or person of '
     'suitable age and discretion could not be found at the leasehold premises. '
     '(Posted and certified mailed, no one present at leasehold premises.)\n\n'
     'Mailed To: {{lot.address}}, Lot {{lot.lot_number}}, {{property.city}}, {{property.state}} {{property.zip}}\n\n'
     'Executed on this <rule w="8"> day of <rule w="18">, {{instrument.execution_year}}, '
     'at <rule w="20">, Washington.\n\n'
     '<rule w="40">\n'
     'Signature\n\n'
     '<rule w="30"> (Printed)\n'
     'Agent for {{landlord.name}}',
 NULL,
 NULL,
 FALSE,
 NULL,
 '0199a003-0000-7000-8000-000000000006',
 NULL,
 NULL,
 TRUE,
 NULL,
 'The source form requires a service date and serving agent. No current DocumentToken represents either value, so both remain fill-in fields.',
 '00000000-0000-7000-8000-000000000002'),


-- 7. Spacer / closing
('0199a002-0000-7000-8000-000000000201',
 '0199a001-0000-7000-8000-000000000008',
 70,
 'INCREASE_NOTICE_CLOSING',
 NULL,
 E'{{property.community_name}}\n'
     '{{landlord.name}}',
 NULL,
 NULL,
 FALSE,
 NULL,
 NULL,
 NULL,
 NULL,
 FALSE,
 NULL,
 NULL,
 '00000000-0000-7000-8000-000000000002');