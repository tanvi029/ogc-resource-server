# coding: utf-8

"""
    OGC Compliant IUDX Resource Server

    OGC compliant Features and Common API definitions. Includes Schema and Response Objects.

    The version of the OpenAPI document: 1.0.1
    Contact: info@iudx.org.in
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


from __future__ import annotations
import pprint
import re  # noqa: F401
import json

from pydantic import BaseModel, ConfigDict, Field
from typing import Any, ClassVar, Dict, List, Union
from typing_extensions import Annotated
from typing import Optional, Set
from typing_extensions import Self

class TileMatrixSetTileMatricesInnerVariableMatrixWidthsInner(BaseModel):
    """
    Variable Matrix Width data structure
    """ # noqa: E501
    coalesce: Union[Annotated[float, Field(multiple_of=1, strict=True, ge=2)], Annotated[int, Field(strict=True, ge=2)]] = Field(description="Number of tiles in width that coalesce in a single tile for these rows")
    min_tile_row: Union[Annotated[float, Field(multiple_of=1, strict=True, ge=0)], Annotated[int, Field(strict=True, ge=0)]] = Field(description="First tile row where the coalescence factor applies for this tilematrix", alias="minTileRow")
    max_tile_row: Union[Annotated[float, Field(multiple_of=1, strict=True, ge=0)], Annotated[int, Field(strict=True, ge=0)]] = Field(description="Last tile row where the coalescence factor applies for this tilematrix", alias="maxTileRow")
    __properties: ClassVar[List[str]] = ["coalesce", "minTileRow", "maxTileRow"]

    model_config = ConfigDict(
        populate_by_name=True,
        validate_assignment=True,
        protected_namespaces=(),
    )


    def to_str(self) -> str:
        """Returns the string representation of the model using alias"""
        return pprint.pformat(self.model_dump(by_alias=True))

    def to_json(self) -> str:
        """Returns the JSON representation of the model using alias"""
        # TODO: pydantic v2: use .model_dump_json(by_alias=True, exclude_unset=True) instead
        return json.dumps(self.to_dict())

    @classmethod
    def from_json(cls, json_str: str) -> Optional[Self]:
        """Create an instance of TileMatrixSetTileMatricesInnerVariableMatrixWidthsInner from a JSON string"""
        return cls.from_dict(json.loads(json_str))

    def to_dict(self) -> Dict[str, Any]:
        """Return the dictionary representation of the model using alias.

        This has the following differences from calling pydantic's
        `self.model_dump(by_alias=True)`:

        * `None` is only added to the output dict for nullable fields that
          were set at model initialization. Other fields with value `None`
          are ignored.
        """
        excluded_fields: Set[str] = set([
        ])

        _dict = self.model_dump(
            by_alias=True,
            exclude=excluded_fields,
            exclude_none=True,
        )
        return _dict

    @classmethod
    def from_dict(cls, obj: Optional[Dict[str, Any]]) -> Optional[Self]:
        """Create an instance of TileMatrixSetTileMatricesInnerVariableMatrixWidthsInner from a dict"""
        if obj is None:
            return None

        if not isinstance(obj, dict):
            return cls.model_validate(obj)

        _obj = cls.model_validate({
            "coalesce": obj.get("coalesce"),
            "minTileRow": obj.get("minTileRow"),
            "maxTileRow": obj.get("maxTileRow")
        })
        return _obj


