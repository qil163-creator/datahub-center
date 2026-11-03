#!/usr/bin/env python3
"""以真实上传、双向TLS存储RPC和集成接口验证文件字段；不输出令牌或文件内容。"""
import argparse
from io import BytesIO
import json
from pathlib import Path
import ssl
import urllib.error
import urllib.request
import uuid
import zipfile


def request(url, context=None, data=None, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}), context=context, timeout=30) as response:
        return json.load(response)


def workbook():
    entries = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
        '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        'xl/workbook.xml': '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets></workbook>',
        'xl/_rels/workbook.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
        'xl/worksheets/sheet1.xml': '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>忽略</t></is></c><c r="B1" t="inlineStr"><is><t>编号</t></is></c><c r="C1" t="inlineStr"><is><t>名称</t></is></c><c r="D1" t="inlineStr"><is><t>启用</t></is></c><c r="E1" t="inlineStr"><is><t>公式</t></is></c></row><row r="2"><c r="B2"><v>1</v></c><c r="C2" t="inlineStr"><is><t>河道A</t></is></c><c r="D2" t="b"><v>1</v></c><c r="E2"><f>1+2</f><v>3</v></c></row><row r="3"><c r="B3"><v>2</v></c><c r="C3" t="inlineStr"><is><t>q\'Data</t></is></c><c r="D3" t="b"><v>0</v></c></row></sheetData></worksheet>',
    }
    output = BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, text in entries.items():
            archive.writestr(name, text.encode('utf-8'))
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-directory', default='/tmp/datahub-file-schema-tls')
    parser.add_argument('--integration-url', default='http://127.0.0.1:50025')
    parser.add_argument('--evidence', default='verification/file-schema-api.json')
    args = parser.parse_args()
    folder = Path(args.private_directory)
    context = ssl.create_default_context(cafile=str(folder / 'ca.crt'))
    context.load_cert_chain(str(folder / 'client.crt'), str(folder / 'client.key'))
    # 仅本仓库已启用的本地mock协议，不写入提交文件或证据。
    headers = {'Authorization': 'Bearer test1', 'tenant-id': '1'}
    evidence = {'infra': 'https://localhost:48085', 'integration': args.integration_url, 'transport': 'mutual TLS; pinned client certificate; hostname and CA verification', 'cases': {}}

    def upload(name, content):
        boundary = 'datahub-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + content + ('\r\n--' + boundary + '--\r\n').encode()
        response = request('https://localhost:48085/admin-api/infra/file/upload-with-info', context, body, {**headers, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
        assert response['code'] == 0, {'case': 'upload', 'code': response['code'], 'message': response.get('msg')}
        return response['data']['id']

    def parse(file_id, format, **options):
        response = request(args.integration_url + '/admin-api/dataforge/integration/file-schema', data=json.dumps({'fileId': str(file_id), 'format': format, **options}).encode(), headers={**headers, 'Content-Type': 'application/json'})
        return response

    csv_content = '编号,"站名,别称",日期\r\n0012,"河道\nA",2026-11-01\r\n'.encode('utf-8')
    (folder / 'sample.csv').write_bytes(csv_content)
    csv_id = upload('sample.csv', csv_content)
    csv_result = parse(csv_id, 'CSV')
    assert csv_result['code'] == 0, csv_result
    assert [(field['name'], field.get('dataType')) for field in csv_result['data']['fields']] == [('编号', 'STRING'), ('站名,别称', 'STRING'), ('日期', 'STRING')]
    assert csv_result['data']['partial'] is True and csv_result['data']['warnings']
    evidence['cases']['csv'] = {'fileId': str(csv_id), 'result': csv_result['data'], 'asserted': True}
    assert parse(csv_id, 'JSONL')['code'] != 0
    evidence['cases']['wrongFormatDenied'] = {'asserted': True}

    json_content = b'{"id":1,"name":"0012","nested":{},"empty":null}\n{"id":2,"name":"q\'Data","nested":[],"empty":null}\n'
    (folder / 'sample.jsonl').write_bytes(json_content)
    json_id = upload('sample.jsonl', json_content)
    result = parse(json_id, 'JSONL')
    assert result['code'] == 0, result
    data = result['data']
    assert data['partial'] is True and data['warnings']
    assert [(field['name'], field.get('dataType')) for field in data['fields']] == [('id', 'LONG'), ('name', 'STRING'), ('nested', None), ('empty', None)]
    assert all(field.get(key) is None for field in data['fields'] for key in ['length', 'precision', 'scale'])
    evidence['cases']['jsonl'] = {'fileId': str(json_id), 'result': data, 'asserted': True}

    excel_content = workbook()
    (folder / 'sample.xlsx').write_bytes(excel_content)
    excel_id = upload('sample.xlsx', excel_content)
    result = parse(excel_id, 'EXCEL', startRow=2, startColumn=2, sheetName='数据')
    assert result['code'] == 0, result
    data = result['data']
    assert [(field['name'], field.get('dataType')) for field in data['fields']] == [('编号', 'LONG'), ('名称', 'STRING'), ('启用', 'BOOLEAN'), ('公式', None)]
    assert data['partial'] is True
    evidence['cases']['excel'] = {'fileId': str(excel_id), 'result': data, 'formulaNotEvaluated': True, 'asserted': True}
    no_header = parse(excel_id, 'EXCEL', startRow=1, startColumn=2)
    assert no_header['code'] == 0 and no_header['data']['fields'][0]['name'] == 'column_2'
    assert no_header['data']['fields'][0].get('dataType') is None
    evidence['cases']['excelNoHeader'] = {'preservesFirstRow': True, 'generatedColumn': 'column_2', 'mixedTypeUnknown': True, 'asserted': True}

    response = request('https://localhost:48085/rpc-api/infra/file/managed/user-upload?fileId=' + str(json_id) + '&actorId=2', context)
    assert response['code'] != 0
    evidence['cases']['otherUploaderDenied'] = {'code': response['code'], 'asserted': True}
    no_certificate = ssl.create_default_context(cafile=str(folder / 'ca.crt'))
    try:
        request('https://localhost:48085/rpc-api/infra/file/managed/user-upload?fileId=' + str(json_id) + '&actorId=1', no_certificate)
        raise AssertionError('client certificate unexpectedly optional')
    except (urllib.error.URLError, ssl.SSLError):
        evidence['cases']['missingCertificateDenied'] = {'asserted': True}
    malformed_id = upload('malformed.jsonl', b'{"a":1,"a":2}\n')
    malformed = parse(malformed_id, 'JSONL')
    assert malformed['code'] != 0
    evidence['cases']['duplicateKeyDenied'] = {'code': malformed['code'], 'asserted': True}
    target = Path(args.evidence)
    if not target.is_absolute():
        target = Path(__file__).parent / target
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'evidence': str(target), 'cases': list(evidence['cases']), 'asserted': True}))


if __name__ == '__main__':
    main()
